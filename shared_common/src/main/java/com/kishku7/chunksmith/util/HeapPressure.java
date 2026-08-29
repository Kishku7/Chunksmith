package com.kishku7.chunksmith.util;

/**
 * Reads heap occupancy off {@link Runtime} and decides whether a pregen may keep dispatching.
 *
 * <p>Three releases went into bounding a pregen by counting proxies: queued writes,
 * LOD-sink depth, resident chunks, chunks added since the run started. Every one was
 * wrong on a real server. An absolute chunk cap fired on chunks that were never ours;
 * a delta cap did not fire at all while the heap filled on a run resumed on an
 * already-loaded server. What actually ends a pregen badly is running out of memory,
 * and a chunk is worth wildly different amounts of heap.
 *
 * <p>{@code used = total - free} counts garbage not yet collected, so one sample can
 * read high on a healthy server, but a collector always runs before the heap fills, so
 * a heap that stays high for several seconds holds live data. And the gate only pauses
 * dispatch, so a false positive costs seconds of throughput while a false negative
 * costs the server: {@link #CONFIRM_SAMPLES} samples close it, and it opens well below
 * the threshold.
 */
public final class HeapPressure {

    /** Consecutive over-threshold samples required before the gate closes. */
    public static final int CONFIRM_SAMPLES = 3;

    /** How far below the threshold the heap must fall before dispatch resumes. */
    public static final int RESUME_MARGIN_PERCENT = 15;

    private static int consecutiveHigh;

    private HeapPressure() {
    }

    public static double usedPercent() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max <= 0L) {
            return -1.0D;
        }
        long used = runtime.totalMemory() - runtime.freeMemory();
        return 100.0D * used / max;
    }

    public static long usedMegabytes() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
    }

    /** The JVM's maximum heap in megabytes as set by the operator with {@code -Xmx}. */
    public static long maxMegabytes() {
        return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    }

    /**
     * Checks whether dispatch should be held off right now.
     *
     * @param currentlyHeld    whether the gate is already closed, so the resume margin can be applied
     * @param thresholdPercent the configured ceiling, or 0 to disable the gate entirely
     * @return true when dispatch should be held off
     */
    public static boolean shouldHold(boolean currentlyHeld, long thresholdPercent) {
        return shouldHold(currentlyHeld, thresholdPercent, usedPercent());
    }

    /**
     * Reading-injecting overload. The confirmation streak and the resume margin are
     * the whole point of this class and cannot be tested against a live heap, since a
     * test cannot make the JVM sit at 90 percent.
     */
    static boolean shouldHold(boolean currentlyHeld, long thresholdPercent, double used) {
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
            double resumeAt = Math.max(50.0D, thresholdPercent - RESUME_MARGIN_PERCENT);
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

    /** Clears the confirmation streak. Called when a run starts, so no run inherits another's state. */
    public static void reset() {
        consecutiveHigh = 0;
    }

    /** Returns how many consecutive high samples have been seen. Test seam. */
    static int consecutiveHigh() {
        return consecutiveHigh;
    }
}
