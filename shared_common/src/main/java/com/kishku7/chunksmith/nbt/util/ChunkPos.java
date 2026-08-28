package com.kishku7.chunksmith.nbt.util;

public record ChunkPos(int x, int z) {
    public static ChunkPos of(int x, int z) {
        return new ChunkPos(x, z);
    }
}
