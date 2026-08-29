/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.util;

import java.util.Objects;

public class Version implements Comparable<Version> {
    public static final Version INVALID = new Version(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    public static final Version MINECRAFT_1_13_2 = new Version(1, 13, 2);
    private int major = 0, minor = 0, patch = 0;

    public Version(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public Version(String version) {
        if (version == null || version.isEmpty()) {
            this.major = Integer.MIN_VALUE;
            return;
        }
        String[] semVer = version.split("\\.");
        if (semVer.length > 0) {
            this.major = Input.tryInteger(semVer[0]).orElse(Integer.MIN_VALUE);
        }
        if (semVer.length > 1) {
            this.minor = Input.tryInteger(semVer[1]).orElse(Integer.MIN_VALUE);
        }
        if (semVer.length > 2) {
            this.patch = Input.tryInteger(semVer[2]).orElse(Integer.MIN_VALUE);
        }
    }

    public Version(String version, boolean minecraft) {
        this(minecraft && version.indexOf('-') > -1 ? version.substring(0, version.indexOf('-')) : version);
    }

    public boolean isEqualTo(Version o) {
        return compareTo(o) == 0;
    }

    public boolean isHigherThan(Version o) {
        return compareTo(o) > 0;
    }

    public boolean isHigherThanOrEqualTo(Version o) {
        return compareTo(o) >= 0;
    }

    public boolean isLowerThan(Version o) {
        return compareTo(o) < 0;
    }

    public boolean isLowerThanOrEqualTo(Version o) {
        return compareTo(o) <= 0;
    }

    public boolean isValid() {
        return major != Integer.MIN_VALUE && minor != Integer.MIN_VALUE && patch != Integer.MIN_VALUE;
    }

    @Override
    public int compareTo(Version o) {
        if (this.major != o.major) {
            return this.major - o.major;
        }
        if (this.minor != o.minor) {
            return this.minor - o.minor;
        }
        return this.patch - o.patch;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Version version = (Version) o;
        return major == version.major && minor == version.minor && patch == version.patch;
    }

    /**
     * Returns {@code major.minor.patch}, or {@code invalid} for {@link #INVALID}.
     *
     * <p>A value class with equals and hashCode and no toString is a trap: the
     * first thing that renders one prints an object identity instead. {@code
     * /cs status} did exactly that on a live server: "Chunksmith
     * com.kishku7.chunksmith.util.Version@8154".
     */
    @Override
    public String toString() {
        return isValid() ? (major + "." + minor + "." + patch) : "invalid";
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }
}
