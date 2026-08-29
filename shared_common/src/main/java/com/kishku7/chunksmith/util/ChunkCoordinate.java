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
import java.util.Optional;

public record ChunkCoordinate(int x, int z) implements Comparable<ChunkCoordinate> {
    public static Optional<ChunkCoordinate> fromRegionFile(String regionFileName) {
        if (!regionFileName.startsWith("r.")) {
            return Optional.empty();
        }
        int extension = regionFileName.indexOf(".mca");
        if (extension < 2) {
            return Optional.empty();
        }
        String regionCoordinates = regionFileName.substring(2, extension);
        int separator = regionCoordinates.indexOf('.');
        Optional<Integer> regionX = Input.tryInteger(regionCoordinates.substring(0, separator));
        Optional<Integer> regionZ = Input.tryInteger(regionCoordinates.substring(separator + 1));
        if (regionX.isPresent() && regionZ.isPresent()) {
            return Optional.of(new ChunkCoordinate(regionX.get(), regionZ.get()));
        }
        return Optional.empty();
    }

    @Override
    public int compareTo(ChunkCoordinate o) {
        return this.x == o.x ? Integer.compare(this.z, o.z) : Integer.compare(this.x, o.x);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ChunkCoordinate that = (ChunkCoordinate) o;
        return x == that.x && z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, z);
    }

    @Override
    public String toString() {
        return String.format("%d, %d", x, z);
    }
}
