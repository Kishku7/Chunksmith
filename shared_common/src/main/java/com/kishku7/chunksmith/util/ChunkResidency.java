package com.kishku7.chunksmith.util;

/**
 * How many chunks the server is holding in memory, and whether a pregen still owes the server a drain.
 *
 * <p><b>Why this exists.</b> Every other throttle signal Chunksmith has measures how fast work goes IN
 * -- tick time, per-chunk latency, the write queue, the LOD sink. None measured what had piled up and
 * not gone OUT. On a live server (2026-08-19) a pregen ran with 75,045 chunk holders resident, roughly
 * ten times the sweep frontier the run could need, and nothing in the mod could see it.
 *
 * <p><b>What 3.5.0 got wrong, and this class now carries.</b> Two things, both measured on Zion
 * 2026-08-20:
 *
 * <p><i>Absolute counts are meaningless.</i> A cap of "20,000 resident" tripped on a server whose
 * ordinary resident set was already near that, so the gate closed on the baseline and never opened,
 * stuttering the run at the never-wedge interval. The question was never "how many chunks exist" -- it
 * is <b>how many have WE added</b>. Hence {@link #baseline()}, captured when a run starts, and a gate
 * that reads the DELTA.
 *
 * <p><i>The backlog outlives the task.</i> 3.5.0 drove the chunk-system unload pass every tick while a
 * task was ACTIVE. When the task paused, that stopped -- and the remaining backlog was left to vanilla's
 * own budgeted pass, which does approximately nothing once the tick is over budget, which it is
 * precisely BECAUSE of the retained chunks. Measured result: 39,064 chunks still resident nineteen
 * minutes after the pregen stopped, with no players online, 51 ms per tick, and the heap pinned at
 * 8.7 GB -- permanent until a restart. A pregen that ends owes the server a drain, so {@link
 * #isDraining()} keeps the unload pass running until the chunks are actually gone.
 *
 * <p><b>Staleness is the point, not a flaw.</b> The value is stamped with the time it was published. A
 * server whose main thread has stopped ticking stops publishing, and its chunks are not unloading
 * either, so a recent-but-frozen reading is still the truth. Only a reading older than {@link
 * #FRESH_MILLIS} is discarded -- by then the server is stopped or wedged far past anything a throttle
 * can help with, and a stale number must not gate a later run.
 *
 * <p>Static because there is one server per process; volatile because the generation task reads from a
 * worker thread while the server thread publishes.
 */
public final class ChunkResidency {

    /** How long a published reading stays usable. Beyond this the count is treated as unknown. */
    public static final long FRESH_MILLIS = 60_000L;

    /**
     * How much of the run's own growth may remain before a drain is called done.
     *
     * <p>Not zero: chunks a PLAYER is holding, and chunks the world legitimately keeps, are counted in
     * the same number, and demanding an exact return to baseline would keep the unload pass armed for
     * ever on any server somebody is actually playing on.
     */
    private static final long DRAIN_MARGIN = 512L;

    /** Give up draining if the count has not fallen at all for this long -- the rest is pinned. */
    private static final long DRAIN_STALL_MILLIS = 30_000L;

    /** Absolute ceiling on a drain, so a pathological world cannot arm the unload pass for ever. */
    private static final long DRAIN_MAX_MILLIS = 10 * 60_000L;

    private static volatile long loadedChunks = -1L;
    private static volatile long reportedAtMillis;

    private static volatile long baseline = -1L;

    private static volatile boolean draining;
    private static volatile long drainStartedMillis;
    private static volatile long drainBestSeen = Long.MAX_VALUE;
    private static volatile long drainProgressMillis;

    private ChunkResidency() {
    }

    /**
     * Publish the current resident chunk count. Called from the server thread once per tick.
     *
     * <p>Published ALWAYS, not only while a run is active: after 3.5.0 the number was cleared the
     * moment a task ended, which is exactly when the backlog it left behind most needed watching.
     *
     * @param loaded total chunk holders across every level, or negative when the platform cannot say
     */
    public static void report(final long loaded) {
        report(loaded, System.currentTimeMillis());
    }

    /**
     * Time-injecting overload. The drain has three timing-based exits and every one of them is a
     * never-wedge guarantee, so they have to be testable without a clock that really passes.
     */
    static void report(final long loaded, final long now) {
        if (loaded < 0L) {
            return;
        }
        loadedChunks = loaded;
        reportedAtMillis = now;
        if (draining) {
            evaluateDrain(loaded, now);
        }
    }

    /**
     * The most recent resident chunk count, or -1 when there is no usable reading.
     *
     * <p>-1 means "unknown", never "zero": a caller must not read an absent measurement as an empty
     * server and open the taps.
     */
    public static long loadedChunks() {
        return loadedChunksAt(System.currentTimeMillis());
    }

    static long loadedChunksAt(final long now) {
        final long value = loadedChunks;
        if (value < 0L) {
            return -1L;
        }
        if (now - reportedAtMillis > FRESH_MILLIS) {
            return -1L;
        }
        return value;
    }

    /** True when the platform is reporting at all -- i.e. the residency signal can be used. */
    public static boolean isSupported() {
        return loadedChunks() >= 0L;
    }

    /**
     * Residency when the current run started, or -1 if it was not measurable.
     *
     * <p>This is the number the gate subtracts. Everything already resident when a run begins belongs
     * to the server, not to us, and gating on it would be gating on somebody else's chunks.
     */
    public static long baseline() {
        return baseline;
    }

    /** How many chunks this run has added, or -1 when either end of the subtraction is unknown. */
    public static long addedChunks() {
        final long now = loadedChunks();
        final long base = baseline;
        if (now < 0L || base < 0L) {
            return -1L;
        }
        return Math.max(0L, now - base);
    }

    /** A run is starting: remember what the server was holding before we touched it. */
    public static void noteTaskStart() {
        noteTaskStart(System.currentTimeMillis());
    }

    static void noteTaskStart(final long now) {
        baseline = loadedChunksAt(now);
        draining = false;
        drainBestSeen = Long.MAX_VALUE;
    }

    /**
     * A run has ended. Keep the unload pass armed until the chunks it loaded have actually gone.
     *
     * <p>This is the whole fix for the orphaned backlog. Ending a task is not the same as finishing the
     * work: the tickets come back immediately, but a released ticket only becomes a freed chunk once
     * the distance manager has propagated it and the unload pass has run.
     */
    public static void noteTaskEnd() {
        noteTaskEnd(System.currentTimeMillis());
    }

    static void noteTaskEnd(final long now) {
        draining = true;
        drainStartedMillis = now;
        drainProgressMillis = now;
        final long current = loadedChunksAt(now);
        drainBestSeen = current < 0L ? Long.MAX_VALUE : current;
    }

    /** True while a finished run still owes the server a drain. */
    public static boolean isDraining() {
        return draining;
    }

    /** Forget everything. Called when the server stops, so no reading outlives its server. */
    public static void clear() {
        loadedChunks = -1L;
        reportedAtMillis = 0L;
        baseline = -1L;
        draining = false;
        drainBestSeen = Long.MAX_VALUE;
        drainStartedMillis = 0L;
        drainProgressMillis = 0L;
    }

    /**
     * Decide whether the drain is finished.
     *
     * <p>Three ways to stop, and all three are needed. Reaching the baseline is success. No progress
     * for {@link #DRAIN_STALL_MILLIS} means the remainder is pinned by something that is not ours --
     * players, spawn chunks, another mod's tickets -- and no amount of further unload passes will move
     * it. The absolute ceiling is the backstop for a world that manages to trickle downward for ever.
     */
    private static void evaluateDrain(final long loaded, final long now) {
        final long base = baseline;
        if (base >= 0L && loaded <= base + DRAIN_MARGIN) {
            draining = false;
            return;
        }
        if (loaded < drainBestSeen) {
            drainBestSeen = loaded;
            drainProgressMillis = now;
        } else if (now - drainProgressMillis >= DRAIN_STALL_MILLIS) {
            draining = false;
            return;
        }
        if (now - drainStartedMillis >= DRAIN_MAX_MILLIS) {
            draining = false;
        }
    }
}
