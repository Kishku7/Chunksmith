package com.kishku7.chunksmith.util;

import org.junit.Before;
import org.junit.Test;

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
}
