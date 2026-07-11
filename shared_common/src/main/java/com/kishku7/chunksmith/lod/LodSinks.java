package com.kishku7.chunksmith.lod;

/**
 * Holder for the active {@link LodSink}.
 *
 * <p>The platform layer resolves the real sink (it is the only side that can see voxy / a chunk) and
 * publishes it here, so MC-agnostic shared code -- notably the generation throttle -- can read the
 * sink's queue depth without depending on any platform type.
 *
 * <p>Defaults to {@link LodSink#NOOP}, so shared code is always safe to call.
 */
public final class LodSinks {

    private static volatile LodSink active = LodSink.NOOP;

    private LodSinks() {
    }

    /** The active sink. Never null. */
    public static LodSink get() {
        return active;
    }

    /** Publish the active sink. Passing null resets to {@link LodSink#NOOP}. */
    public static void set(final LodSink sink) {
        active = sink == null ? LodSink.NOOP : sink;
    }
}
