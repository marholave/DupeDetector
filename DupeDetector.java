/*
	This file is part of DupeDetector.
	DupeDetector is a command-line tool that finds duplicate files.
	Copyright © 2011, 2012, 2016, 2017, 2019 Mark Holland-Avery

	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <https://www.gnu.org/licenses/>.

	SPDX-License-Identifier: GPL-3.0-or-later
*/

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.Scanner;

/**
 * A command-line tool that quickly finds exact duplicates among the specified files and folders.
 *
 * Its speed comes from minimising the amount of data that must be read from each file.
 * First, it makes a list of files whose sizes are not unique, then it sorts the list by size.
 * When it finds exactly two files with the same size, it compares their content directly.
 * This comparison stops reading at the first difference found, if any. When it finds a group of
 * three or more same-sized files, it reads them all the way through to compute their digests,
 * then searches for matching digests within the group.
 *
 * This source code compiles with Java 1.5 and later, since this program is particularly useful on
 * older computers that are short of space. Licensing info is in the ReadMe.txt and source files.
 *
 * @author Mark Holland-Avery
 * @version 2019.02.27
 */
public class DupeDetector
{
	/** This class is all static. */
	private DupeDetector() {}

	/** The number of bytes in a kibibyte. */
	private static final long KIB = 1024;

	/** The minimum number of nanoseconds to wait between printing progress updates. */
	private static final long NANOS_TWEEN_MESSAGES = (long)2e8;

	/** The name of the help file resource in the JAR file. */
	private static final String HELP_FILE = "ReadMe.txt";

	/** The text encoding of the help file. */
	private static final String HELP_ENCODING = "UTF-8";

	/** Visually group lines into messages by printing this at the start of a line if it /isn't/ the start of a message. */
	private static final String INDENT = "  ";

	/** Print this at the end of a line to return the terminal's cursor to the beginning of the line. */
	private static final String OVERTYPE = "\u001b[F";

	/** Compilation settings for the path exclusion regex. With Java 1.7 we could also use UNICODE_CHARACTER_CLASS. */
	private static final int EXCLUSION_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.CANON_EQ | Pattern.DOTALL;

	/** The number of files scanned in the first stage. */
	private static int numScanned = 0;

	/** The number of files remaining to be read. */
	private static int numPending = 0;

	/** The number of non-fatal errors encountered. */
	private static int errors = 0;

	/** The time (in nanoseconds) after which a progress update is due to be printed. Set to zero to print very soon. */
	private static long nextTime = 0;

	/**
	 * The program's entry point.
	 * @param args command-line arguments
	 */
	public static void main(final String[] args)
	{
		try
		{
			// The files specified by command-line arguments end up in this list.
			final ArrayList<File> files = new ArrayList<File>();

			// Files whose paths match this will be ignored later.
			final StringBuilder excludedPaths = new StringBuilder();

			// Extract, from the command-line arguments, the program's settings and a list of files to scan.
			final long minFileSize = readArgs(args, files, excludedPaths);

			// The regex that determines which paths are ignored.
			final Matcher excludedPathMatcher = prepareExclusions(excludedPaths);

			// Print help when given nothing to scan.
			if (files.isEmpty())
			{
				final Scanner help = new Scanner(ClassLoader.getSystemResourceAsStream(HELP_FILE), HELP_ENCODING);
				while (help.hasNextLine())
				{
					System.err.println(help.nextLine());
				}
				return;
			}

			// Look inside the specified files and folders to make a list of files to test for duplicates.
			final ArrayList<FileBox> fileBoxes = findCandidateFiles(files, excludedPathMatcher, minFileSize);

			// Find files whose sizes are not unique; they could be exact duplicates.
			final ArrayList<ArrayList<FileBox>> groups = findSameSizedFiles(fileBoxes);

			// Read the files in each group to find duplicate files.
			findDuplicates(groups);
		}
		catch (final IllegalArgumentException exception)
		{
			System.err.println("Error: " + exception.getLocalizedMessage());
			final Throwable cause = exception.getCause();
			if (cause != null)
			{
				System.err.println(cause.getLocalizedMessage());
			}
			System.err.println("For help, run DupeDetector with no arguments or see the " + HELP_FILE + " file.");
			System.exit(1);
		}
	}

	/**
	 * Extracts, from the command-line arguments, the program's settings and a list of files to scan.
	 * @param args command-line arguments
	 * @param files a list to which the files will be appended
	 * @param excludedPaths The regex will be appended to this StringBuilder
	 * @return the minimum file size in bytes
	 */
	private static long readArgs(final String[] args, final ArrayList<File> files, final StringBuilder excludedPaths)
	{
		boolean pathsOnly = false;

		// Files whose sizes (in bytes) are smaller than this will be ignored later.
		long minFileSize = 1;

		// Arguments that match this will be added to the excludedPaths regex.
		final Matcher excludeMatcher = Pattern.compile("--exclude=(.+)", Pattern.DOTALL).matcher("");

		// Arguments that match this will set the minFileSize. The last matching argument takes precedence.
		final Matcher sizeMatcher = Pattern.compile("--min=(\\d*\\.?\\d+)(?i)([kmg]i?)?b?").matcher("");

		for (final String arg: args)
		{
			if (pathsOnly)
			{
				files.add(readFilePath(arg, true));
			}
			else if (arg.equals("--"))
			{
				pathsOnly = true;
			}
			// See if the argument matches a pattern.
			else if (excludeMatcher.reset(arg).matches())
			{
				excludedPaths.append('|').append(excludeMatcher.group(1));
			}
			else if (sizeMatcher.reset(arg).matches())
			{
				minFileSize = readFileSize(sizeMatcher.group(1), sizeMatcher.group(2));
			}
			else // The argument must be a path.
			{
				files.add(readFilePath(arg, false));
			}
		}

		return minFileSize;
	}

	/**
	 * Converts the specified number and unit strings into a size in bytes.
	 * @param size the size in whatever units are specified
	 * @param unit the units with which to scale the specified size
	 * @return the size in bytes
	 */
	private static long readFileSize(final String size, final String unit)
	{
		// The regex should ensure that this does not throw NumberFormatException.
		double bytes = Double.parseDouble(size);

		// Multiply the bytes by the unit, if any.
		// Using an if-else ladder (instead of a switch) for Java 1.5 compatibility.
		if ("k".equalsIgnoreCase(unit))
		{
			bytes *= 1e3;
		}
		else if ("ki".equalsIgnoreCase(unit))
		{
			bytes *= KIB;
		}
		else if ("m".equalsIgnoreCase(unit))
		{
			bytes *= 1e6;
		}
		else if ("mi".equalsIgnoreCase(unit))
		{
			bytes *= KIB * KIB;
		}
		else if ("g".equalsIgnoreCase(unit))
		{
			bytes *= 1e9;
		}
		else if ("gi".equalsIgnoreCase(unit))
		{
			bytes *= KIB * KIB * KIB;
		}
		else if (unit != null)
		{
			// sizeMatcher should prevent this from ever happening.
			throw new IllegalArgumentException("Programmer error: Unknown unit of file size: " + unit);
		}

		// Round up any fraction of a byte so that we don't find files that are slightly smaller than the specified minimum.
		return (long)Math.ceil(bytes);
	}

	/**
	 * Transforms the specified regex for the user's convenience and compiles it for later use.
	 * @param rawRegex the regex as extracted from the command-line arguments
	 * @return the compiled form of the regex
	 */
	private static Matcher prepareExclusions(final StringBuilder rawRegex)
	{
		if (rawRegex.length() == 0)
		{
			return null;
		}

		// The user tends to care about the end (file name) of the path but not the beginning.
		// To save them from adding this optional group (that matches anything ending with the file separator)
		// to the beginning of all their patterns, we do that for them.
		// If they do want to match the whole path then they can just begin the string they enter with '^'.
		// Also, we remove that leading pipe character; that's just from concatenation earlier on.
		String regex = "(.*" + Pattern.quote(File.separator) + ")?(" + rawRegex.substring(1) + ")";

		// Windows, MS-DOS, OS/2 and Symbian systems use backslashes as path separators and don't allow slashes in file names.
		// Since backslashes must be escaped, which is annoying, these systems accept slashes instead and so do we.
		if (File.separatorChar == '\\')
		{
			regex = regex.replace("/", "\\\\");
		}
		try
		{
			return Pattern.compile(regex, EXCLUSION_FLAGS).matcher(""); // throws PatternSyntaxException.
		}
		catch (final PatternSyntaxException exception)
		{
			throw new IllegalArgumentException("Please check the syntax of your regex.", exception);
		}
	}

	/**
	 * Returns a File representing the canonical form of the specified path. Throws an exception if the file cannot be read.
	 * @param path the path to normalise
	 * @param pathsOnly Selects the error message if the file cannot be read.
	 * Set it to true if the path must point to a file, or false if it could instead be a malformed command-line argument
	 * @return the canonical path
	 */
	private static File readFilePath(final String path, final boolean pathsOnly)
	{
		final File file = new File(path);
		if (!file.canRead())
		{
			if (pathsOnly)
			{
				throw new IllegalArgumentException("This file could not be read: " + path);
			}
			else
			{
				// This message is vague because it happens when path is not a readable file or valid command-line switch.
				throw new IllegalArgumentException("This is not a valid option or a readable file: " + path);
			}
		}
		try
		{
			// Return the canonical form of the file so that it passes the symlink test in scan().
			// Command-line arguments are the only place where we follow symlinks.
			return file.getCanonicalFile();
		}
		catch (final IOException exception)
		{
			throw new IllegalArgumentException("This file's canonical path could not be determined: " + file, exception);
		}
	}

	/**
	 * Looks inside the specified files and folders to make a list of files to test for duplicates.
	 * @param files the list of files and folders to scan
	 * @param excludedPaths Paths that match this regex will be ignored
	 * @param minFileSize the minimum file size in bytes. Smaller files will be ignored
	 * @return the list of files (not folders) to be tested for duplicates
	 */
	private static ArrayList<FileBox> findCandidateFiles(final ArrayList<File> files, final Matcher excludedPaths, final long minFileSize)
	{
		// The set of files and folders that have already been scanned.
		final HashSet<File> scannedFiles = new HashSet<File>();

		// The list of files to be searched for duplicates.
		final ArrayList<FileBox> fileBoxes = new ArrayList<FileBox>();

		// Recursively scan all the specified files and folders.
		for (final File file: files)
		{
			scan(file, scannedFiles, fileBoxes, excludedPaths, minFileSize);
		}
		System.err.println("Total files scanned: " + numScanned);

		return fileBoxes;
	}

	/**
	 * Adds the specified file or, if it is a folder, the files inside it to a list for further processing.
	 * @param file the file or folder to scan
	 * @param scannedFiles Files that have been scanned will be added to this list so that they won't be scanned again
	 * @param fileBoxes Files that need further tests will be added to this list
	 * @param excludedPaths Paths that match this regex will be ignored
	 * @param minFileSize the minimum file size in bytes. Smaller files will be ignored
	 */
	private static void scan(final File file, final HashSet<File> scannedFiles, final ArrayList<FileBox> fileBoxes, final Matcher excludedPaths, final long minFileSize)
	{
		if (excludedPaths == null || !excludedPaths.reset(file.toString()).matches())
		{
			// Get a canonical reference to the file.
			File canonicalFile = null;
			try
			{
				canonicalFile = file.getCanonicalFile(); // Throws IOException.
			}
			catch (final IOException exception)
			{
				complain("This file could not be compared because its canonical path could not be determined: " + file, exception);
			}

			// Skip symlinks to stay within the specified folders and find only real duplicates.
			// A file is a symlink if its absolute path does not equal its canonical path.
			// This test is backward-compatible with Java 1.5.
			// Also skip files we've seen before because of redundant command-line arguments.
			if (canonicalFile != null && file.getAbsoluteFile().equals(canonicalFile) && scannedFiles.add(canonicalFile))
			{
				// If this is a folder then scan the files in it.
				final File[] innerFiles = file.listFiles();
				if (innerFiles != null)
				{
					for (final File innerFile: innerFiles)
					{
						scan(innerFile, scannedFiles, fileBoxes, excludedPaths, minFileSize);
					}
				}

				// Else if it's a normal file then get its size for later use.
				// We ignore files that we can't read (how would we tell if they
				// were duplicates?) and files with negligible sizes.
				else if (file.isFile())
				{
					final long size = file.length();
					if (size >= minFileSize && file.canRead())
					{
						fileBoxes.add(new FileBox(file, size));
					}
				}
			}
		}

		// Update the progress readout if enough time has passed.
		numScanned++;
		final long time = System.nanoTime();
		if (time >= nextTime)
		{
			// Work out when to update the progress readout next time.
			nextTime = time + NANOS_TWEEN_MESSAGES;

			// Update the progress readout. The leading space stops the cursor from covering the text.
			// Since the number is increasing, it will always overtype itself completely.
			System.err.println(INDENT + "Files scanned: " + numScanned + OVERTYPE);
		}
	}

	/**
	 * Accepts a list of files and returns a list of lists where the files in a given sub-list are all the same size.
	 * @param fileBoxes the list of files to be grouped
	 * @return groups of same-sized files, excluding files with unique sizes
	 */
	private static ArrayList<ArrayList<FileBox>> findSameSizedFiles(final ArrayList<FileBox> fileBoxes)
	{
		final ArrayList<ArrayList<FileBox>> groups = new ArrayList<ArrayList<FileBox>>();

		final int numFiles = fileBoxes.size();
		if (numFiles < 2)
		{
			return groups;
		}

		// Using an anonymous inner class (instead of a lambda expression or method reference) for Java 1.5 compatibility.
		final Comparator<FileBox> sizeComparator = new Comparator<FileBox>()
		{
			public int compare(final FileBox file0, final FileBox file1)
			{
				// In Java 1.7 we could use Long.compare() but for Java 1.5 we must do this.
				// It does not overflow because longs have plenty of headroom for file sizes.
				return Long.signum(file0.getSize() - file1.getSize());
			}
		};

		// Sort files by size.
		Collections.sort(fileBoxes, sizeComparator);

		FileBox previousFile = fileBoxes.get(0);
		ArrayList<FileBox> group = null;

		for (int index = 1; index < numFiles; index++)
		{
			final FileBox file = fileBoxes.get(index);
			if (file.getSize() == previousFile.getSize())
			{
				if (group == null)
				{
					group = new ArrayList<FileBox>();
					group.add(previousFile);
					groups.add(group);
					numPending++;
				}
				group.add(file);
				numPending++;
			}
			else
			{
				previousFile = file;
				group = null;
			}
		}
		return groups;
	}

	/**
	 * Reads the files in each group to find duplicate files. Files must already be grouped by size.
	 * @param groups a list of lists of files grouped by size
	 */
	private static void findDuplicates(final ArrayList<ArrayList<FileBox>> groups)
	{
		System.err.println("Total files to read: " + numPending);

		// Count how many bytes would be freed by keeping one copy of each file and deleting the rest.
		// Note: This program doesn't actually delete anything!
		long wastedBytes = 0;

		for (final ArrayList<FileBox> files: groups)
		{
			// When exactly two files have the same size, compare their content directly.
			// This way, we can stop reading both as soon as different bytes are found.
			if (files.size() == 2)
			{
				wastedBytes += comparePair(files.get(0), files.get(1));
			}
			// When a group of three or more files have the same size, compare them by digest.
			// This saves us from reading each file more than once.
			else
			{
				wastedBytes += compareGroup(files);
			}
		}

		// Print a summary.
		System.err.printf
		(
			"Search complete! Number of errors: %d%n"
			+ "If you deleted all but one file from each group%n"
			+ "then you could reclaim about %s of storage space.%n",
			errors, humaniseSize(wastedBytes)
		);
	}

	/**
	 * Compares two files by content. If they are the same then their paths are printed.
	 * @param file0 one of the files
	 * @param file1 the other file
	 * @return the number of bytes wasted by the duplicate file, or zero if not duplicates
	 */
	private static long comparePair(final FileBox file0, final FileBox file1)
	{
		long wastedBytes = 0;

		if (file0.contentEquals(file1))
		{
			// This line is long enough to overtype the "Files to read" progress countdown.
			System.out.println("2 content-matched " + humaniseSize(file0.getSize()) + " files:");

			// Print results in a consistent order.
			if (file0.comparePathTo(file1) < 0)
			{
				System.out.println(INDENT + file0);
				System.out.println(INDENT + file1);
			}
			else
			{
				System.out.println(INDENT + file1);
				System.out.println(INDENT + file0);
			}
			wastedBytes = file0.getSize();

			// Print numPending sooner.
			nextTime = 0;
		}
		reduceCounter(2);
		return wastedBytes;
	}

	/**
	 * Compares files by their digests and prints lists of matching files. Specified files should be the same size.
	 * @param files the files to compare
	 * @return the number of bytes wasted by duplicate files, not counting the first file in each set of duplicates
	 */
	private static long compareGroup(final ArrayList<FileBox> files)
	{
		// Sort the files by digest so that files that are (probably) the same end up together.
		// This is the point at which files are read and digests are generated.
		Collections.sort(files);

		final ArrayList<FileBox> matches = new ArrayList<FileBox>();
		final int numFiles = files.size();
		FileBox previousFile = files.get(0);
		long wastedBytes = 0;

		for (int index = 1; index < numFiles; index++)
		{
			final FileBox file = files.get(index);
			if (file.digestEquals(previousFile))
			{
				if (matches.isEmpty())
				{
					matches.add(previousFile);
				}
				matches.add(file);
				wastedBytes += file.getSize();
			}
			else if (!matches.isEmpty())
			{
				printDigestMatches(matches);
				matches.clear();
			}
			previousFile = file;
		}

		if (!matches.isEmpty())
		{
			printDigestMatches(matches);
		}

		return wastedBytes;
	}

	/**
	 * Prints a list of files. By this point, all the files in the group are believed to be identical to each other, based on digests.
	 * @param files the files to print
	 */
	private static void printDigestMatches(final ArrayList<FileBox> files)
	{
		// This line is long enough to overtype the "Files to read" progress countdown.
		System.out.println(files.size() + " digest-matched " + humaniseSize(files.get(0).getSize()) + " files:");

		for (final FileBox file: files)
		{
			System.out.println(INDENT + file);
		}

		// Print numPending sooner.
		nextTime = 0;
	}

	/**
	 * Updates the progress readout for when files are being read, as opposed to scanned.
	 * @param decrement how much to decrease the file counter by
	 */
	public static void reduceCounter(final int decrement)
	{
		numPending -= decrement;
		final long time = System.nanoTime();
		if (time >= nextTime)
		{
			// Work out when to update the progress readout next time.
			nextTime = time + NANOS_TWEEN_MESSAGES;

			// Update the progress readout. The leading space stops the cursor from covering the text.
			// Since the number is decreasing, we overtype it with nine spaces, which is the most it could ever shrink.
			System.err.println(INDENT + "Files to read: " + numPending + "         " + OVERTYPE);
		}
	}

	/**
	 * Prints the specified exception to the error output and increments the error counter. This is for non-fatal exceptions.
	 * @param message a helpful description of what went wrong
	 * @param exception the exception that was caught
	 */
	public static void complain(final String message, final Exception exception)
	{
		errors++;
		System.err.println("Error: " + message);
		System.err.println(exception.getLocalizedMessage());

		// Print numPending sooner.
		nextTime = 0;
	}

	/**
	 * Converts the specified file size in bytes to a human-readable string with an appropriate unit such as MB.
	 * @param size a file size in bytes
	 * @return a human-readable string
	 */
	private static String humaniseSize(final long size)
	{
		if (size >= 1e9)
		{
			return Math.round(size / 1e9) + " GB";
		}
		else if (size >= 1e6)
		{
			return Math.round(size / 1e6) + " MB";
		}
		else if (size >= 1e3)
		{
			return Math.round(size / 1e3) + " kB";
		}
		else
		{
			return size + " B";
		}
	}
}