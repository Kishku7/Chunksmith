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

/**
 * WHEN to expire chunk tickets while the world-enter pregen holds the tick freeze (issue #37).
 *
 * <p>Vanilla skips the ticket purge while frozen, so every chunk the run touched stays loaded. Left
 * alone that grows ~120 KB live per chunk until the heap guard throttles the run to a crawl. But the
 * loaded chunks are also the neighbours the next ring needs, so keeping them is FASTER while there
 * is room: purging every tick, as an unfrozen server does, made 12,649 new chunks in 15 minutes
 * where keeping them made 22,005 (fresh ground, NeoForge 1.21.1, 4 GB, generational ZGC).
 *
 * <p>So the loaded set is a bounded cache, sized from the max heap: no purge while fewer than
 * {@link #capFor} chunks are resident; above it, purge every tick (vanilla's unfrozen behaviour)
 * until residency is back under {@link #RESUME_FRACTION} of the cap. The count is used rather than
 * a heap reading because it is exact and immediate -- on generational ZGC the "live" estimate is
 * the raw reading most of the time, full of garbage, and a trigger on it stayed on permanently.
 *
 * <p>On a small heap the cap sits near the floor the in-flight work alone keeps resident (~13,000
 * chunks at 4 GB), so the policy is effectively "purge every tick" there: safe, just not faster.
 * The cache pays off on the large heaps the long world-enter runs are actually made on.
 *
 * <p>Pure policy with no Minecraft types, so it is unit-tested directly.
 */
public final class FrozenTicketPurge {

    /** Measured live cost of a resident chunk (vanilla 1.21.1: ~120 KB), rounded up. */
    public static final long BYTES_PER_CHUNK = 128L * 1024L;
    /** Share of the max heap the resident-chunk cache may occupy. */
    public static final double CAP_HEAP_FRACTION = 0.35;
    /** Stop purging once residency is back under this share of the cap. */
    public static final double RESUME_FRACTION = 0.8;
    /**
     * Most of a tick the unload pass may take while frozen (see ChunkMapFreezeUnloadMixin): 10 ms of
     * the 50 ms tick, leaving the rest to the generation tasks the pass otherwise starves.
     */
    public static final long UNLOAD_BUDGET_NANOS = 10_000_000L;

    /**
     * Harness seam: {@code -Dchunksmith.test.frozenPurgeCap=N} replaces the heap-sized cap, so a short
     * gate run can make the purge fire and prove the version-specific call links on every cell.
     */
    private static final int CAP_OVERRIDE = Integer.getInteger("chunksmith.test.frozenPurgeCap", -1);

    private boolean purging;
    private int switchesOn;

    /** The resident-chunk cap for a heap of {@code maxHeapBytes}. */
    public static int capFor(long maxHeapBytes) {
        long cap = (long) (Math.max(0L, maxHeapBytes) * CAP_HEAP_FRACTION) / BYTES_PER_CHUNK;
        return (int) Math.min(Integer.MAX_VALUE, cap);
    }

    /** The cap in force: the harness override if set, else {@link #capFor}. */
    public static int effectiveCap(long maxHeapBytes) {
        return CAP_OVERRIDE >= 0 ? CAP_OVERRIDE : capFor(maxHeapBytes);
    }

    /**
     * Called once per server tick while the world-enter freeze is on.
     *
     * @return true if the stale-ticket purge should run on this tick
     */
    public boolean tick(int resident, long maxHeapBytes) {
        int cap = effectiveCap(maxHeapBytes);
        if (!purging && resident > cap) {
            purging = true;
            switchesOn++;
        } else if (purging && resident <= (int) (cap * RESUME_FRACTION)) {
            purging = false;
        }
        return purging;
    }

    /** Times the purge has switched on since the last reset -- for the log line and tests. */
    public int switchesOn() {
        return switchesOn;
    }

    /** Forget all state; called whenever the freeze is not on, so each run starts caching. */
    public void reset() {
        purging = false;
        switchesOn = 0;
    }
}