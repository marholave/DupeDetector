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

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * A wrapper for a file that can compute the file's digest and be sorted by it.
 *
 * @author Mark Holland-Avery
 * @version 2019.02.27
 */
public class FileBox implements Comparable<FileBox>
{
	/** A buffer size that is big but still runs quickly on my old Core Duo MacBook. */
	private static final int BUFFER_SIZE = 0x80000;

	/**
	 * A digest algorithm chosen to make collisions unlikely.
	 * Available algorithms may be listed in the MessageDigest documentation.
	 */
	private static final String DIGEST_ALGORITHM = "SHA-256";

	/** A special value to indicate that a digest could not be generated. */
	private static final ByteBuffer DIGEST_ERROR = ByteBuffer.allocate(0);

	/** FileBox reuses two buffers to reduce garbage collection when reading files. This is not thread-safe. */
	private static final byte[] buffer0 = new byte[BUFFER_SIZE], buffer1 = new byte[BUFFER_SIZE];

	/** The file represented by this instance of the wrapper. */
	private final File file;

	/** The length, in bytes, of the file's main data stream. (Other streams are ignored.) */
	private final long size;

	/** The file's digest, which is generated on demand and cached here. */
	private ByteBuffer digest = null;

	/**
	 * Wraps the file in an object that can compute the file's digest and be sorted by it.
	 * @param file the file to wrap
	 * @param size the file's size in bytes, which should be known at this point
	 */
	public FileBox(final File file, final long size)
	{
		this.file = file;
		this.size = size;
	}

	/**
	 * Returns this file's path.
	 * @return the file's path
	 */
	@Override public String toString()
	{
		return file.getPath();
	}

	/**
	 * Returns the size of the file when this wrapper was created.
	 * This value is cached for both speed and consistent sorting.
	 * @return the file's length in bytes
	 */
	public long getSize()
	{
		return size;
	}

	/**
	 * Returns true if both files have the same digest, indicating a probable match.
	 * @param otherBox the other file to compare this one to
	 * @return true if the digests match
	 */
	public boolean digestEquals(final FileBox otherBox)
	{
		final ByteBuffer digest = getDigest();
		return digest != DIGEST_ERROR && digest.equals(otherBox.getDigest());
	}

	/**
	 * Compares this file to the specified one for the purpose of sorting by digest
	 * (for spotting probable duplicates) and then by path (for collating the results).
	 * This function should be used to sort groups of three or more files that have
	 * the same size, so that they can be quickly matched by digest rather than
	 * slowly compared to each other by contentEquals().
	 * @param otherBox the other file to compare this one to
	 * @return zero if both digests match and both paths are the same,
	 * negative if this file sorts before the argument, or positive if this file sorts after
	 */
	public int compareTo(final FileBox otherBox)
	{
		final int result = getDigest().compareTo(otherBox.getDigest());
		if (result == 0)
		{
			return file.compareTo(otherBox.file);
		}
		return result;
	}

	/**
	 * Determines which file path comes first alphabetically.
	 * @param otherBox the other file to compare this one to
	 * @return zero if both paths are the same, negative if this path comes before the argument,
	  * or positive if this path comes after
	 */
	public int comparePathTo(final FileBox otherBox)
	{
		return file.compareTo(otherBox.file);
	}

	/**
	 * Compares the content of this file's main data stream to that of the specified file,
	 * looking for an exact match. This function will return false as soon as the first
	 * difference is found, but will read all of both files if they are the same, which can be slow.
	 * For speed, call this function only when exactly two files have the same size.
	 * Groups of three or more files should instead be sorted by digest using compareTo().
	 * @param otherBox the other file to compare this one to
	 * @return true if both files have identical content
	 */
	public boolean contentEquals(final FileBox otherBox)
	{
		// Check file sizes first, for speed. By this point, the program has
		// already determined that the files were the same size during the initial scan,
		// but we check again here in case they have changed.
		if (file.length() != otherBox.file.length())
		{
			return false;
		}

		// Since the files are the same size, we need to compare their content.
		boolean isEqual;
		FileInputStream stream0 = null, stream1 = null;
		try
		{
			int numBytes0, numBytes1;
			stream0 = new FileInputStream(file); // Throws FileNotFoundException.
			stream1 = new FileInputStream(otherBox.file); // Also throws.
			do
			{
				// Read both files into buffers and find out how much we read.
				numBytes0 = stream0.read(buffer0); // Throws IOException.
				numBytes1 = stream1.read(buffer1); // Also throws.

				// So far, the files look the same as long as we read the
				// same amount from both and the data we read was the same.
				isEqual = numBytes0 == numBytes1 && Arrays.equals(buffer0, buffer1);
			}
			// Continue the test as long as the files look the same
			// and we haven't reached the end of either yet.
			while (isEqual && numBytes0 == BUFFER_SIZE);
		}
		// Catch IOException and its subclass FileNotFoundException.
		catch (final IOException exception)
		{
			DupeDetector.complain("These two files were not compared because one of them could not be read: " + file + " and " + otherBox.file, exception);
			isEqual = false;
		}

		// The files are considered equal if they have the same content
		// and we successfully close them, meaning that we read them all
		// the way through without errors. Note that we must use non-short-
		// circuit AND operators so that we always try to close both.
		return isEqual & close(stream0) & close(stream1);
	}

	/**
	 * Returns this file's digest. This is slow the first time it is called on a given file.
	 * After that, the return value is cached for both speed and consistent sorting.
	 * @return the file's digest
	 */
	private ByteBuffer getDigest()
	{
		// Return the digest if we've already generated it.
		if (digest != null)
		{
			return digest;
		}

		// Prepare to generate the digest.
		DigestInputStream stream = null;
		try
		{
			// Make the stream that will generate the digest while reading the file.
			final MessageDigest digester = MessageDigest.getInstance(DIGEST_ALGORITHM); // Throws NoSuchAlgorithmException.
			stream = new DigestInputStream(new FileInputStream(file), digester); // Throws FileNotFoundException.

			// Keep reading the file as long as there is data to read.
			while (stream.read(buffer0) == BUFFER_SIZE); // Throws IOException.

			// Wrap the digest in a ByteBuffer so that it can be sorted.
			digest = ByteBuffer.wrap(digester.digest());
		}
		catch (final NoSuchAlgorithmException exception)
		{
			throw new IllegalArgumentException("This digest algorithm is not available:" + DIGEST_ALGORITHM, exception);
		}
		// Catch IOException and its subclass FileNotFoundException.
		catch (final IOException exception)
		{
			DupeDetector.complain("This file was not compared because it could not be read: " + file, exception);
			digest = DIGEST_ERROR;
		}

		if (!close(stream))
		{
			// We couldn't close the file so maybe we didn't read it all.
			digest = DIGEST_ERROR;
		}

		DupeDetector.reduceCounter(1);
		return digest;
	}

	/**
	 * Tries to close the specified object if not null. If an IOException is thrown
	 * then it is logged and the function returns normally. This is a workaround
	 * for the lack of a try-with-resources construct before Java 1.7.
	 * This is superior in that no exceptions are suppressed; all are logged.
	 * @param closeable the object to close
	 * @return true if the object closed successfully
	 */
	private boolean close(final Closeable closeable)
	{
		if (closeable == null)
		{
			return false;
		}
		try
		{
			closeable.close(); // Throws IOException.
			return true;
		}
		catch (final IOException exception)
		{
			DupeDetector.complain("A stream could not be closed. This may have been caused by a previous error.", exception);
			return false;
		}
	}
}