package com.kishku7.chunksmith.util;

/**
 * How many chunks the server is currently holding in memory, published once per tick by the platform.
 *
 * <p><b>Why this exists.</b> Every other throttle signal Chunksmith has measures how fast work is going
 * IN -- tick time, per-chunk latency, the write queue, the LOD sink. None of them measures what has piled
 * up and not yet gone OUT. That gap is a real failure, observed on a live server (2026-08-19): a pregen
 * ran with 75,045 chunk holders resident in the overworld, roughly ten times the sweep frontier the run
 * could legitimately need, and nothing in the mod could see it.
 *
 * <p>It is also self-reinforcing, which is what makes it worth a signal of its own. Vanilla's unload pass
 * is budgeted by the server's own per-tick time allowance, so a server that has fallen behind does almost
 * no unloading; the resident set grows; ticking a larger resident set costs more; the server falls
 * further behind. Meanwhile the existing throttle reads the high tick time, cuts dispatch to its floor,
 * and -- because a settle window's releases are driven by new arrivals -- slows the very mechanism that
 * hands chunks back. Every signal we had said "slow down", and slowing down made it worse.
 *
 * <p>So the number itself has to be visible. The platform reports it each tick; {@code GenerationTask}
 * treats a resident set past its bound the same way it treats a stalled write queue -- stop dispatching
 * until it drains.
 *
 * <p><b>Staleness is the point, not a flaw.</b> The value is stamped with the time it was published. A
 * server whose main thread has stopped ticking stops publishing, and its chunks are not unloading either,
 * so a recent-but-frozen reading is still the truth. Only a reading older than {@link #FRESH_MILLIS} is
 * discarded -- by then the server is either stopped or wedged far past anything a throttle can help with,
 * and a stale number must not gate a later run.
 */
public final class ChunkResidency {

    /** How long a published reading stays usable. Beyond this the count is treated as unknown. */
    public static final long FRESH_MILLIS = 60_000L;

    private static volatile long loadedChunks = -1L;
    private static volatile long reportedAtMillis;

    private ChunkResidency() {
    }

    /**
     * Publish the current resident chunk count. Called from the server thread once per tick.
     *
     * @param loaded total chunk holders across every level, or negative when the platform cannot say
     */
    public static void report(final long loaded) {
        if (loaded < 0L) {
            return;
        }
        loadedChunks = loaded;
        reportedAtMillis = System.currentTimeMillis();
    }

    /**
     * The most recent resident chunk count, or -1 when there is no usable reading.
     *
     * <p>-1 means "unknown", never "zero": a caller must not read an absent measurement as an empty
     * server and open the taps.
     */
    public static long loadedChunks() {
        final long value = loadedChunks;
        if (value < 0L) {
            return -1L;
        }
        if (System.currentTimeMillis() - reportedAtMillis > FRESH_MILLIS) {
            return -1L;
        }
        return value;
    }

    /** True when the platform is reporting at all -- i.e. the residency signal can be used. */
    public static boolean isSupported() {
        return loadedChunks() >= 0L;
    }

    /** Forget the current reading. Called when the server stops, so no reading outlives its server. */
    public static void clear() {
        loadedChunks = -1L;
        reportedAtMillis = 0L;
    }
}
