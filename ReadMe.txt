DupeDetector is a command-line tool that finds duplicate files.
Version 2019.02.27

Usage:

java -jar DupeDetector.jar [--min=SIZE] [--exclude=REGEX] [--] FILE ...

FILE is a path to a file, folder or symlink to scan for duplicate files.
Symlinks that you specify are followed but symlinks found in folders are not.

SIZE is a minimum file size in bytes. Files smaller than SIZE will be ignored.
You can use a decimal point and append a unit: B, kB, KiB, MB, MiB, GB or GiB.
SIZE is case-insensitive and its default value is 1B.
Examples: --min=1500kb --min=1.5mb --min=256kib --min=0.25mib
If --min=SIZE is repeated then the last one will be used.

REGEX is a regular expression. Files and folders whose paths match it will be
ignored. DupeDetector will not look inside them or identify them as duplicates.
REGEX is a normal Java regular expression, which is Perl-compatible.
Usually, you should enclose your 'REGEX' in single quotes (') to escape it.
If --exclude='REGEX' is repeated then all of them will be used to ignore files:
--exclude='abc' --exclude='xyz' is equivalent to --exclude='abc|xyz' as below.

REGEX Tips:

'.' matches any character, not just a dot!
'.*' matches any string of characters, including zero-length strings.
'\.' matches only a dot because...
'\' makes the next character match literally, if it had a special meaning.
'\\' matches one backslash. On Windows, '/' also matches a backslash.
'abc|xyz' (with a pipe '|') matches 'abc' or 'xyz'. Both paths will be ignored.

Advanced REGEX Tips:

REGEX is case-insensitive unless it starts with '(?-i)'.
REGEX is tested against the end of each path unless it starts with '^'.
Then it will be tested against whole paths instead.

REGEX Examples:

To ignore files whose names end in '.bak' or '.BAK': --exclude='.*\.bak'
To ignore dot-files that are hidden on Unix-like systems: --exclude='\..*'
To ignore the hidden files that store folder settings on Mac and Windows:
--exclude='\.DS_Store|Desktop\.ini'

More Info:

For help with regular expressions, search the web for 'java regex' or 'pcre'.
You can find a few more personal projects at https://holland-avery.com/
and e-mail me at marholave@gmail.com

DupeDetector License:

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
