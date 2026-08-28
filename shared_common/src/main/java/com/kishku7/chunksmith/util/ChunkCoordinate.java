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
