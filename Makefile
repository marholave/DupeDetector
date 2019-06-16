#   This file is part of DupeDetector.
#   DupeDetector is a command-line tool that finds duplicate files.
#   Copyright © 2011, 2012, 2016, 2017, 2019 Mark Holland-Avery
#
#   This program is free software: you can redistribute it and/or modify
#   it under the terms of the GNU General Public License as published by
#   the Free Software Foundation, either version 3 of the License, or
#   (at your option) any later version.
#
#   This program is distributed in the hope that it will be useful,
#   but WITHOUT ANY WARRANTY; without even the implied warranty of
#   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#   GNU General Public License for more details.
#
#   You should have received a copy of the GNU General Public License
#   along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
#   SPDX-License-Identifier: GPL-3.0-or-later

DupeDetector.jar: Makefile Manifest.txt ReadMe.txt ./*.java
	javac -encoding UTF-8 -Xlint -Xlint:-serial DupeDetector.java
	jar cfm DupeDetector.jar Manifest.txt ReadMe.txt ./*.class
