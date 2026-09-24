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

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The gate that must not cry wolf and must not sleep through a fire.
 *
 * <p>{@code used = total - free} includes garbage nobody has
 * collected yet, so one high sample proves nothing; hence the
 * confirmation streak. And releasing the moment the heap dips
 * back under the threshold would put the run straight back
 * over it -- hence the resume margin. Both are tested here
 * against the real {@link Runtime}, because the point of this
 * class is that it has no seams to fake: a threshold of 0 and
 * a threshold of 1 bracket every possible live reading.
 */
public class HeapPressureTest {

    @Before
    public void reset() {
        HeapPressure.reset();
    }

    @Test
    public void zeroThresholdIsOff() {
        for (int i = 0; i < 10; i++) {
            assertFalse("threshold 0 is off", HeapPressure.shouldHold(false, 0L, 99.0D));
        }
        assertFalse(HeapPressure.shouldHold(true, 0L, 99.0D));
    }

    @Test
    public void oneSampleIsNotEnough() {
        assertFalse("one high sample is not enough",
                HeapPressure.shouldHold(false, 85L, 92.0D));
    }

    @Test
    public void sustainedHighHolds() {
        boolean held = false;
        for (int i = 0; i < HeapPressure.CONFIRM_SAMPLES; i++) {
            held = HeapPressure.shouldHold(false, 85L, 92.0D);
        }
        assertTrue("a sustained high reading holds", held);
    }

    @Test
    public void aDipBreaksTheStreak() {
        HeapPressure.shouldHold(false, 85L, 92.0D);
        HeapPressure.shouldHold(false, 85L, 92.0D);
        HeapPressure.shouldHold(false, 85L, 40.0D);
        assertFalse("a dip restarts the streak",
                HeapPressure.shouldHold(false, 85L, 92.0D));
    }

    @Test
    public void aHealthyHeapNeverTripsIt() {
        for (int i = 0; i < 10; i++) {
            assertFalse(HeapPressure.shouldHold(false, 85L, 40.0D));
        }
    }

    @Test
    public void needsRealHeadroomToResume() {
        for (int i = 0; i < HeapPressure.CONFIRM_SAMPLES; i++) {
            HeapPressure.shouldHold(false, 85L, 92.0D);
        }
        assertTrue("still over the threshold", HeapPressure.shouldHold(true, 85L, 92.0D));
        assertTrue("inside the resume margin",
                HeapPressure.shouldHold(true, 85L, 82.0D));
        assertFalse("real headroom at last", HeapPressure.shouldHold(true, 85L, 69.0D));
    }

    @Test
    public void anUnreadableHeapIsNotTreatedAsAFullOne() {
        assertFalse(HeapPressure.shouldHold(false, 85L, -1.0D));
        assertFalse(HeapPressure.shouldHold(true, 85L, -1.0D));
    }

    @Test
    public void resetForgetsIt() {
        for (int i = 0; i < HeapPressure.CONFIRM_SAMPLES - 1; i++) {
            HeapPressure.shouldHold(false, 85L, 92.0D);
        }
        HeapPressure.reset();
        assertFalse("the streak started over", HeapPressure.shouldHold(false, 85L, 92.0D));
    }

    @Test
    public void reportsSaneNumbers() {
        assertTrue(HeapPressure.maxMegabytes() > 0L);
        assertTrue(HeapPressure.usedMegabytes() >= 0L);
        assertTrue(HeapPressure.usedMegabytes() <= HeapPressure.maxMegabytes());
        double percent = HeapPressure.usedPercent();
        assertTrue(percent >= 0.0D && percent <= 100.0D);
    }

    // ---- mod_support #37: a hold nothing can end ----

    private static final long T0 = 1_000_000L;
    private static final long MB = 1024L * 1024L;
    private static final long MAX = 2048L * MB;

    /**
     * Two pools shaped like generational ZGC: a young one that minor cycles refresh constantly, and an
     * old one only a major cycle touches. Arguments are what each pool's LAST collection left.
     */
    private static HeapPressure.Pools zgc(long youngAfterMb, long oldAfterMb) {
        return new HeapPressure.Pools(MAX,
                new long[] {youngAfterMb * MB, oldAfterMb * MB},
                new long[] {300L * MB, 1600L * MB},
                new long[] {200L * MB, 1600L * MB});
    }

    private static void closeAt(HeapPressure.Pools pools) {
        for (int i = 0; i < HeapPressure.CONFIRM_SAMPLES; i++) {
            HeapPressure.shouldHold(false, 85L, 88.0D, pools, T0);
        }
    }

    /**
     * The reporter's timeline, as generational ZGC plays it: the gate closes at 88%, minor cycles
     * keep running, the old generation is never collected, and the reading never moves. Before
     * 4.3.4 this held for as long as the world stayed frozen.
     */
    @Test
    public void anOldGenerationNobodyCollectsLetsGoAfterTheLimit() {
        closeAt(zgc(50L, 1500L));
        long almost = T0 + HeapPressure.NO_COLLECTION_RELEASE_MS - 1L;
        // young refreshed (a minor cycle ran), old untouched
        assertTrue("still inside the wait", HeapPressure.shouldHold(true, 85L, 88.0D, zgc(40L, 1500L), almost));
        assertFalse("the old generation has not been looked at: stop waiting on a number nothing moves",
                HeapPressure.shouldHold(true, 85L, 88.0D, zgc(30L, 1500L), T0 + HeapPressure.NO_COLLECTION_RELEASE_MS));
        assertTrue("and it says so", HeapPressure.consumeReleasedBlind());
        assertFalse("once", HeapPressure.consumeReleasedBlind());
        assertEquals("it names what went uncollected", 1600L, HeapPressure.staleMegabytes());
    }

    /** Every large pool collected since the gate closed, still full: real pressure, keep holding. */
    @Test
    public void everyPoolCollectedAndStillFullKeepsHolding() {
        closeAt(zgc(50L, 1500L));
        assertTrue(HeapPressure.shouldHold(true, 85L, 88.0D, zgc(40L, 1550L), T0 + 5_000L));
        assertEquals(0L, HeapPressure.staleMegabytes());
        assertTrue("the wait restarts from that round of collections",
                HeapPressure.shouldHold(true, 85L, 88.0D, zgc(30L, 1550L), T0 + HeapPressure.NO_COLLECTION_RELEASE_MS + 1L));
        assertFalse(HeapPressure.consumeReleasedBlind());
        assertFalse("but not for ever once the old generation goes uncollected again",
                HeapPressure.shouldHold(true, 85L, 88.0D, zgc(20L, 1550L), T0 + 5_000L + HeapPressure.NO_COLLECTION_RELEASE_MS));
    }

    /** A small pool going uncollected cannot hold the gate: it is not where the memory is. */
    @Test
    public void aSmallUncollectedPoolDoesNotCount() {
        HeapPressure.Pools pools = new HeapPressure.Pools(MAX,
                new long[] {1500L * MB, 10L * MB}, new long[] {1600L * MB, 20L * MB},
                new long[] {1600L * MB, 20L * MB});
        closeAt(pools);
        HeapPressure.Pools collected = new HeapPressure.Pools(MAX,
                new long[] {1550L * MB, 10L * MB}, new long[] {1600L * MB, 20L * MB},
                new long[] {1600L * MB, 20L * MB});
        assertTrue(HeapPressure.shouldHold(true, 85L, 88.0D, collected, T0 + 20_000L));
        assertEquals(0L, HeapPressure.staleMegabytes());
    }

    /** Real headroom still ends a hold straight away, and that is not a blind release. */
    @Test
    public void headroomStillEndsItNormally() {
        closeAt(zgc(50L, 1500L));
        assertFalse(HeapPressure.shouldHold(true, 85L, 60.0D, zgc(50L, 1500L), T0 + 1_000L));
        assertFalse(HeapPressure.consumeReleasedBlind());
    }

    /** With no pool data the old behaviour stands: no release on a clock we cannot justify. */
    @Test
    public void unknownPoolsNeverReleaseOnTheClock() {
        for (int i = 0; i < HeapPressure.CONFIRM_SAMPLES; i++) {
            HeapPressure.shouldHold(false, 85L, 88.0D, null, T0);
        }
        assertTrue(HeapPressure.shouldHold(true, 85L, 88.0D, null, T0 + 10L * HeapPressure.NO_COLLECTION_RELEASE_MS));
    }

    @Test
    public void heldMillisReportsTheWholeHold() {
        closeAt(zgc(50L, 1500L));
        HeapPressure.shouldHold(true, 85L, 88.0D, zgc(40L, 1550L), T0 + 5_000L);
        assertEquals("a full round of collections does not restart the reported duration",
                7_000L, HeapPressure.heldMillis(T0 + 7_000L));
        assertEquals("but it does restart the wait, and the log says which it means",
                2_000L, HeapPressure.waitingMillis(T0 + 7_000L));
        HeapPressure.reset();
        assertEquals(0L, HeapPressure.waitingMillis(T0 + 7_000L));
        assertEquals(0L, HeapPressure.heldMillis(T0 + 7_000L));
    }

    @Test
    public void theGateDecidesOnTheLowerReading() {
        assertEquals("garbage is not pressure", 40.0D, HeapPressure.liveEstimate(88.0D, 40.0D), 0.0D);
        assertEquals(60.0D, HeapPressure.liveEstimate(60.0D, 80.0D), 0.0D);
        assertEquals("no collection data: the raw number, as before",
                88.0D, HeapPressure.liveEstimate(88.0D, -1.0D), 0.0D);
        assertEquals(-1.0D, HeapPressure.liveEstimate(-1.0D, 40.0D), 0.0D);
    }

    /** G1 reports its old region as never collected until a mixed or full collection. */
    @Test
    public void aPoolNeverCollectedCountsAtItsCurrentSize() {
        HeapPressure.Pools g1 = new HeapPressure.Pools(1000L,
                new long[] {100L, 0L}, new long[] {500L, 0L}, new long[] {700L, 600L});
        assertEquals(70.0D, HeapPressure.afterCollectionPercent(g1), 0.0001D);
    }

    @Test
    public void noCollectedPoolAtAllIsUnknown() {
        assertEquals(-1.0D, HeapPressure.afterCollectionPercent(new HeapPressure.Pools(1000L,
                new long[] {-1L, 0L}, new long[] {0L, 0L}, new long[] {300L, 300L})), 0.0D);
        assertEquals(-1.0D, HeapPressure.afterCollectionPercent(null), 0.0D);
    }

    /** Against the real JVM: once something has been collected there is a number, and it is sane. */
    @Test
    public void theLiveReadingsAreSane() {
        System.gc();
        double after = HeapPressure.afterCollectionPercent();
        assertTrue("-1 or a percent: " + after, after == -1.0D || (after >= 0.0D && after <= 100.0D));
        double live = HeapPressure.liveEstimatePercent();
        assertTrue(live >= 0.0D && live <= 100.0D);
        assertTrue(HeapPressure.readPools() == null || HeapPressure.readPools().currentUsed.length > 0);
    }
}
