package com.kishku7.chunksmith.util;

/**
 * How full the heap is -- the constraint everything else in this mod has been talking around.
 *
 * <p><b>Why this exists.</b> Chunksmith has spent three releases trying to bound a pregen by COUNTING
 * things: queued writes, LOD-sink depth, resident chunks, chunks added since the run started. Every one
 * of those is a proxy, and every one of them has now been wrong on a real server in a different way.
 * An absolute chunk cap fired on chunks that were never ours; a delta cap did not fire at all while the
 * heap filled, because the run had been resumed on an already-loaded server. The thing that actually
 * ends a pregen badly is not a chunk count -- it is running out of memory, and a chunk is worth wildly
 * different amounts of heap depending on how many entities and block entities came with it. So measure
 * the heap.
 *
 * <p><b>Why the reading is trustworthy despite garbage.</b> {@code used = total - free} counts garbage
 * that has not been collected yet, so a single sample can read high on a perfectly healthy server. Two
 * things make this usable anyway. A collector will always run before it lets the heap actually fill, so
 * a heap that STAYS above the threshold across several seconds of samples is holding live data, not
 * garbage. And the gate only ever pauses dispatch: a false positive costs a few seconds of throughput
 * and nothing else, while a false negative costs the server. {@link #CONFIRM_SAMPLES} consecutive
 * samples are required before the gate closes, and it opens again well below the threshold.
 *
 * <p>Deliberately MC-free and dependency-free -- {@link Runtime} is the whole implementation -- so it is
 * unit-testable and identical on every loader and every Minecraft version.
 */
public final class HeapPressure {

    /** Consecutive over-threshold samples required before the gate closes. */
    public static final int CONFIRM_SAMPLES = 3;

    /** How far below the threshold the heap must fall before dispatch resumes. */
    public static final int RESUME_MARGIN_PERCENT = 15;

    private static int consecutiveHigh;

    private HeapPressure() {
    }

    /** Live heap usage as a percentage of the maximum the JVM is allowed to use. */
    public static double usedPercent() {
        final Runtime runtime = Runtime.getRuntime();
        final long max = runtime.maxMemory();
        if (max <= 0L) {
            return -1.0D;
        }
        final long used = runtime.totalMemory() - runtime.freeMemory();
        return 100.0D * used / max;
    }

    /** Used heap in megabytes, for a message an operator can act on. */
    public static long usedMegabytes() {
        final Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
    }

    /** The JVM's maximum heap in megabytes -- the number the operator set with {@code -Xmx}. */
    public static long maxMegabytes() {
        return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    }

    /**
     * Should dispatch be held off right now?
     *
     * @param currentlyHeld whether the gate is already closed, so the resume margin can be applied
     * @param thresholdPercent the configured ceiling, or 0 to disable the gate entirely
     * @return true when generation should stop adding to the heap
     */
    public static boolean shouldHold(final boolean currentlyHeld, final long thresholdPercent) {
        return shouldHold(currentlyHeld, thresholdPercent, usedPercent());
    }

    /**
     * Reading-injecting overload. The confirmation streak and the resume margin are the whole point of
     * this class and they cannot be tested against a live heap, because a test cannot make the JVM sit
     * at 90% on demand. Everything above is a thin wrapper around this.
     */
    static boolean shouldHold(final boolean currentlyHeld, final long thresholdPercent, final double used) {
        if (thresholdPercent <= 0L) {
            consecutiveHigh = 0;
            return false;
        }
        if (used < 0.0D) {
            consecutiveHigh = 0;
            return false;
        }
        if (currentlyHeld) {
            // Hysteresis. Releasing the moment it dips back under the threshold would put us straight
            // back over it, so require real headroom before generating again.
            final double resumeAt = Math.max(50.0D, thresholdPercent - RESUME_MARGIN_PERCENT);
            if (used <= resumeAt) {
                consecutiveHigh = 0;
                return false;
            }
            return true;
        }
        if (used >= thresholdPercent) {
            consecutiveHigh++;
            return consecutiveHigh >= CONFIRM_SAMPLES;
        }
        consecutiveHigh = 0;
        return false;
    }

    /** Forget the confirmation streak. Called when a run starts, so no run inherits another's state. */
    public static void reset() {
        consecutiveHigh = 0;
    }

    /** Test seam: how many consecutive high samples have been seen. */
    static int consecutiveHigh() {
        return consecutiveHigh;
    }
}
