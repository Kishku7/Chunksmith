package com.kishku7.chunksmith.lod;

public final class LodSinks {

    private static volatile LodSink active = LodSink.NOOP;

    private LodSinks() {
    }

    public static LodSink get() {
        return active;
    }

    public static void set(final LodSink sink) {
        active = sink == null ? LodSink.NOOP : sink;
    }
}
