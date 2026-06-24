package com.kishku7.chunksmith.iterator;

import com.kishku7.chunksmith.util.ChunkCoordinate;

import java.util.Iterator;

public interface ChunkIterator extends Iterator<ChunkCoordinate> {
    long total();

    String name();

    default boolean process() {
        return true;
    }
}
