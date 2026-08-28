package com.kishku7.chunksmith.nbt.util;

public final class ChunkFilter {
    private final byte type;
    private final String name;

    private ChunkFilter(byte type, String name) {
        this.type = type;
        this.name = name;
    }

    public static ChunkFilter of(byte type, String name) {
        return new ChunkFilter(type, name);
    }

    public byte getType() {
        return type;
    }

    public String getName() {
        return name;
    }
}
