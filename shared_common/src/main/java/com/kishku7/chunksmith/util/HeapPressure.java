/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Reads heap occupancy and decides whether a pregen may keep dispatching.
 *
 * <p>Three releases went into bounding a pregen by counting proxies: queued writes,
 * LOD-sink depth, resident chunks, chunks added since the run started. Every one was
 * wrong on a real server. An absolute chunk cap fired on chunks that were never ours;
 * a delta cap did not fire at all while the heap filled on a run resumed on an
 * already-loaded server. What actually ends a pregen badly is running out of memory,
 * and a chunk is worth wildly different amounts of heap.
 *
 * <p>{@code total - free} counts garbage the collector has not reclaimed yet. This class
 * used to assume a collector always runs before that matters. It does not: a collector
 * runs when something asks it for memory, and a held pregen on a frozen world asks for
 * almost nothing. The gate closed at 85%, waited for a reading of 70% that only a
 * collection could produce, and the collection never came (mod_support #37: a 16 GB
 * client on generational ZGC held for over ten minutes, until the player entered the
 * world).
 *
 * <p>Counting collections does not answer it either. Generational ZGC runs minor cycles
 * constantly while leaving the old generation, where a pregen's discarded chunks end up,
 * for a major cycle that may be a long way off; G1 does much the same with its old
 * region. So the question is asked per heap POOL: has each pool big enough to matter
 * been collected since the gate closed? If one has not, the reading cannot tell its
 * garbage from live data, and after {@link #NO_COLLECTION_RELEASE_MS} the gate lets
 * generation run -- which is what gets the collector to look at it -- and closes again
 * on the next samples if the memory really is in use. If every such pool HAS been
 * collected and the heap is still full, that is real pressure and the gate holds.
 *
 * <p>The gate still closes and opens on the raw number, because closing late is how a
 * server runs out of memory: the chunks already in flight keep arriving after it closes.
 * A false positive costs seconds of throughput and a false negative costs the server, so
 * {@link #CONFIRM_SAMPLES} samples close it and it opens well below the threshold. The
 * post-collection reading ({@link #liveEstimatePercent()}) is used where waiting is the
 * only risk: deciding whether a PAUSED run may resume.
 */
public final class HeapPressure {

    /** Consecutive over-threshold samples required before the gate closes. */
    public static final int CONFIRM_SAMPLES = 3;

    /** How far below the threshold the heap must fall before dispatch resumes. */
    public static final int RESUME_MARGIN_PERCENT = 15;

    /**
     * How long a hold waits on a pool nobody has collected before it stops waiting. The owner's
     * rule for this controller is that a pause lasting more than ten seconds means some number is
     * wrong, and a reading that nothing is refreshing is exactly that.
     */
    public static final long NO_COLLECTION_RELEASE_MS = 10_000L;

    /** A pool smaller than this share of the heap cannot hold the gate on its own. */
    static final int SIGNIFICANT_POOL_PERCENT = 5;

    private static int consecutiveHigh;
    // The hold: when it began (reported), when its patience clock last started, and what each pool's
    // last collection had left at that moment. A pool whose figure has not changed since has not
    // been collected since.
    private static long holdStart;
    private static long clockStart;
    private static long[] snapAfterUsed;
    private static long[] snapAfterCommitted;
    private static long lastStaleBytes = -1L;
    private static boolean releasedBlind;

    private HeapPressure() {
    }

    /** One reading of every heap pool. */
    static final class Pools {
        final long max;
        final long[] afterUsed;
        final long[] afterCommitted;
        final long[] currentUsed;

        Pools(long max, long[] afterUsed, long[] afterCommitted, long[] currentUsed) {
            this.max = max;
            this.afterUsed = afterUsed;
            this.afterCommitted = afterCommitted;
            this.currentUsed = currentUsed;
        }
    }

    /** The raw reading: {@code total - free} over {@code max}, garbage included. -1 when unreadable. */
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

    /** Reads every heap pool, or null when the JVM will not say. */
    static Pools readPools() {
        long max = Runtime.getRuntime().maxMemory();
        if (max <= 0L) {
            return null;
        }
        try {
            List<MemoryPoolMXBean> beans = ManagementFactory.getMemoryPoolMXBeans();
            int n = 0;
            for (MemoryPoolMXBean pool : beans) {
                if (pool.getType() == MemoryType.HEAP && pool.isValid()) {
                    n++;
                }
            }
            long[] afterUsed = new long[n];
            long[] afterCommitted = new long[n];
            long[] currentUsed = new long[n];
            int i = 0;
            for (MemoryPoolMXBean pool : beans) {
                if (pool.getType() != MemoryType.HEAP || !pool.isValid() || i >= n) {
                    continue;
                }
                MemoryUsage after = pool.getCollectionUsage();
                MemoryUsage now = pool.getUsage();
                afterUsed[i] = after == null ? -1L : after.getUsed();
                afterCommitted[i] = after == null ? 0L : after.getCommitted();
                currentUsed[i] = now == null ? 0L : now.getUsed();
                i++;
            }
            return n == 0 ? null : new Pools(max, afterUsed, afterCommitted, currentUsed);
        } catch (RuntimeException | LinkageError e) {
            // Management beans are absent on some stripped runtimes. Unknown is not zero.
            return null;
        }
    }

    /**
     * Heap occupancy the most recent collection of each pool left behind, as a percent of the
     * maximum heap, or -1 when not one pool reports a collection.
     */
    public static double afterCollectionPercent() {
        return afterCollectionPercent(readPools());
    }

    /**
     * Pure form. A pool that has never been collected (committed 0 after "collection") contributes
     * its CURRENT usage, not zero: we cannot see through it, and treating "no data" as "empty" would
     * switch the guard off on exactly the collector that reports least.
     */
    static double afterCollectionPercent(Pools pools) {
        if (pools == null || pools.max <= 0L) {
            return -1.0D;
        }
        long total = 0L;
        boolean anyCollected = false;
        for (int i = 0; i < pools.currentUsed.length; i++) {
            if (pools.afterUsed[i] >= 0L && pools.afterCommitted[i] > 0L) {
                total += pools.afterUsed[i];
                anyCollected = true;
            } else {
                total += Math.max(0L, pools.currentUsed[i]);
            }
        }
        return anyCollected ? 100.0D * total / pools.max : -1.0D;
    }

    /**
     * The reading the gate decides on: the lower of the raw number and what the last collections
     * left behind. Garbage the collector would reclaim the moment it was asked is not pressure.
     */
    public static double liveEstimatePercent() {
        return liveEstimate(usedPercent(), afterCollectionPercent());
    }

    /** Pure form of {@link #liveEstimatePercent()}. -1 in either means "not known". */
    static double liveEstimate(double raw, double afterCollection) {
        if (raw < 0.0D || afterCollection < 0.0D) {
            return raw;
        }
        return Math.min(raw, afterCollection);
    }

    /**
     * Checks whether dispatch should be held off right now.
     *
     * @param currentlyHeld    whether the gate is already closed, so the resume margin can be applied
     * @param thresholdPercent the configured ceiling, or 0 to disable the gate entirely
     * @return true when dispatch should be held off
     */
    public static boolean shouldHold(boolean currentlyHeld, long thresholdPercent) {
        // The RAW reading closes and opens the gate, exactly as it always has. A lower "live" estimate
        // was tried for both and it let a G1 client at 400-wide dispatch run out of heap: it closed
        // the gate later, and the chunks already in flight did the rest. Closing late is the one
        // mistake this gate cannot afford. The pools are consulted only to decide when WAITING has
        // stopped meaning anything.
        return shouldHold(currentlyHeld, thresholdPercent, usedPercent(), readPools(),
                System.currentTimeMillis());
    }

    /**
     * Reading-injecting overload with no pool data, so the uncollected-pool release never applies.
     * The confirmation streak and the resume margin cannot be tested against a live heap, since a
     * test cannot make the JVM sit at 90 percent.
     */
    static boolean shouldHold(boolean currentlyHeld, long thresholdPercent, double used) {
        return shouldHold(currentlyHeld, thresholdPercent, used, null, 0L);
    }

    /**
     * Fully injected form.
     *
     * @param pools the pool reading, or null when unknown (disables the release)
     * @param now   the clock, in milliseconds
     */
    static boolean shouldHold(boolean currentlyHeld, long thresholdPercent, double used,
                              Pools pools, long now) {
        if (thresholdPercent <= 0L || used < 0.0D) {
            clear();
            return false;
        }
        if (currentlyHeld) {
            // Hysteresis. Releasing the moment it dips back under the threshold would put us straight
            // back over it, so require real headroom before generating again.
            double resumeAt = Math.max(50.0D, thresholdPercent - RESUME_MARGIN_PERCENT);
            if (used <= resumeAt) {
                clear();
                return false;
            }
            if (pools == null || snapAfterUsed == null || snapAfterUsed.length != pools.afterUsed.length) {
                return true;
            }
            long stale = staleBytes(pools);
            lastStaleBytes = stale;
            if (stale == 0L) {
                // Every pool that matters has been collected since the clock started and the heap is
                // still full. That is memory in use. Keep holding, and wait on the next round.
                snapshot(pools);
                clockStart = now;
                return true;
            }
            if (now - clockStart >= NO_COLLECTION_RELEASE_MS) {
                clear();
                releasedBlind = true;
                return false;
            }
            return true;
        }
        if (used >= thresholdPercent) {
            consecutiveHigh++;
            if (consecutiveHigh >= CONFIRM_SAMPLES) {
                holdStart = Math.max(1L, now);
                clockStart = holdStart;
                lastStaleBytes = -1L;
                if (pools != null) {
                    snapshot(pools);
                } else {
                    snapAfterUsed = null;
                    snapAfterCommitted = null;
                }
                return true;
            }
            return false;
        }
        consecutiveHigh = 0;
        return false;
    }

    /** Bytes in significant pools whose last collection is the same one as at the snapshot. */
    static long staleBytes(Pools pools) {
        long significant = pools.max / 100L * SIGNIFICANT_POOL_PERCENT;
        long stale = 0L;
        for (int i = 0; i < pools.currentUsed.length; i++) {
            if (pools.currentUsed[i] >= significant
                    && pools.afterUsed[i] == snapAfterUsed[i]
                    && pools.afterCommitted[i] == snapAfterCommitted[i]) {
                stale += pools.currentUsed[i];
            }
        }
        return stale;
    }

    private static void snapshot(Pools pools) {
        snapAfterUsed = pools.afterUsed.clone();
        snapAfterCommitted = pools.afterCommitted.clone();
    }

    /**
     * True once after a hold was let go because a pool went uncollected, then false until the next
     * one. The caller logs it: a guard that stops guarding has to say so.
     */
    public static boolean consumeReleasedBlind() {
        boolean was = releasedBlind;
        releasedBlind = false;
        return was;
    }

    /** How long the current hold has lasted, or 0 when not holding. */
    public static long heldMillis(long now) {
        return holdStart == 0L ? 0L : Math.max(0L, now - holdStart);
    }

    /**
     * How long the hold has been waiting on the pools it counts as uncollected, or 0 when not
     * holding. Shorter than {@link #heldMillis} once a round in which every large pool was collected
     * has restarted the wait, so a log line can say which of the two it means.
     */
    public static long waitingMillis(long now) {
        return clockStart == 0L ? 0L : Math.max(0L, now - clockStart);
    }

    /** Megabytes the last held sample counted in pools not yet collected in this wait, or -1 when not known. */
    public static long staleMegabytes() {
        return lastStaleBytes < 0L ? -1L : lastStaleBytes / (1024L * 1024L);
    }

    private static void clear() {
        consecutiveHigh = 0;
        holdStart = 0L;
        clockStart = 0L;
        snapAfterUsed = null;
        snapAfterCommitted = null;
    }

    /** Clears all gate state. Called when a run starts, so no run inherits another's state. */
    public static void reset() {
        clear();
        releasedBlind = false;
        lastStaleBytes = -1L;
    }

    /** Returns how many consecutive high samples have been seen. Test seam. */
    static int consecutiveHigh() {
        return consecutiveHigh;
    }
}
