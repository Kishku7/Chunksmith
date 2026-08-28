package com.kishku7.chunksmith.lod;

public final class LodPresence {

    @FunctionalInterface
    public interface Provider {
        CsLodPresenceIndex indexFor(String worldName);
    }

    private static volatile Provider provider;

    private LodPresence() {
    }

    public static void setProvider(final Provider value) {
        provider = value;
    }

    public static CsLodPresenceIndex indexFor(final String worldName) {
        final Provider current = provider;
        return current == null ? null : current.indexFor(worldName);
    }
}
