package com.kishku7.chunksmith;

public final class ChunksmithProvider {
    private static Chunksmith instance;

    private ChunksmithProvider() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    public static Chunksmith get() {
        if (instance == null) {
            throw new IllegalStateException("Chunksmith is not loaded.");
        }
        return instance;
    }

    public static boolean isLoaded() {
        return instance != null;
    }

    static void register(final Chunksmith instance) {
        ChunksmithProvider.instance = instance;
    }

    static void unregister() {
        ChunksmithProvider.instance = null;
    }
}
