package com.kishku7.chunksmith.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Two things must never happen here, and both have already happened once in production.
 *
 * <p>An absent measurement must never read as an empty server: the throttle gates on this number, and
 * a caller that mistakes -1 for 0 opens the taps on exactly the server that could not say how loaded
 * it was.
 *
 * <p>And a finished run must keep owing its drain until the chunks are actually gone. 3.5.0 stopped
 * driving the unload pass the moment a task ended, which left 39,064 chunks resident on an idle server
 * until it was restarted. The drain state machine is what stops that, so every one of its exits is
 * tested against an injected clock rather than a real one.
 */
public class ChunkResidencyTest {

    private static final long T0 = 1_000_000L;

    @Before
    public void reset() {
        ChunkResidency.clear();
    }

    @After
    public void tearDown() {
        ChunkResidency.clear();
    }

    // --- the reading itself ------------------------------------------------------------------

    @Test
    public void unknownBeforeAnythingIsPublished() {
        assertEquals(-1L, ChunkResidency.loadedChunks());
        assertFalse(ChunkResidency.isSupported());
    }

    @Test
    public void reportsWhatWasPublished() {
        ChunkResidency.report(75_045L);
        assertEquals(75_045L, ChunkResidency.loadedChunks());
        assertTrue(ChunkResidency.isSupported());
    }

    @Test
    public void zeroIsARealReadingAndNotAnAbsentOne() {
        ChunkResidency.report(0L);
        assertEquals(0L, ChunkResidency.loadedChunks());
        assertTrue("a genuinely empty server is supported, not unknown", ChunkResidency.isSupported());
    }

    @Test
    public void aPlatformThatCannotSayIsIgnoredRatherThanBelieved() {
        ChunkResidency.report(1_234L);
        ChunkResidency.report(-1L);
        assertEquals("a negative report must not erase a good reading", 1_234L, ChunkResidency.loadedChunks());
    }

    @Test
    public void aStaleReadingIsUnknownNotZero() {
        ChunkResidency.report(5_000L, T0);
        assertEquals(5_000L, ChunkResidency.loadedChunksAt(T0 + ChunkResidency.FRESH_MILLIS));
        assertEquals(-1L, ChunkResidency.loadedChunksAt(T0 + ChunkResidency.FRESH_MILLIS + 1L));
    }

    @Test
    public void clearingMeansNoReadingOutlivesItsServer() {
        ChunkResidency.report(1_234L);
        ChunkResidency.clear();
        assertEquals(-1L, ChunkResidency.loadedChunks());
        assertEquals(-1L, ChunkResidency.baseline());
        assertFalse(ChunkResidency.isDraining());
    }

    // --- the delta, which is what the gate actually reads --------------------------------------

    @Test
    public void addedChunksIsMeasuredAgainstWhatTheServerAlreadyHad() {
        ChunkResidency.report(18_000L);
        ChunkResidency.noteTaskStart();
        assertEquals(18_000L, ChunkResidency.baseline());
        ChunkResidency.report(25_000L);
        assertEquals("only OUR growth counts, not the server's own 18k", 7_000L, ChunkResidency.addedChunks());
    }

    @Test
    public void addedChunksNeverGoesNegativeWhenTheServerShrinksBelowBaseline() {
        ChunkResidency.report(18_000L);
        ChunkResidency.noteTaskStart();
        ChunkResidency.report(9_000L);
        assertEquals(0L, ChunkResidency.addedChunks());
    }

    @Test
    public void addedChunksIsUnknownWithoutABaseline() {
        ChunkResidency.report(25_000L);
        assertEquals(-1L, ChunkResidency.addedChunks());
    }

    // --- the drain, whose whole job is to not be forgotten -------------------------------------

    @Test
    public void aFinishedRunOwesADrainUntilTheChunksAreActuallyGone() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        assertTrue("the task ended; the work has not", ChunkResidency.isDraining());

        ChunkResidency.report(30_000L, T0 + 3_000L);
        assertTrue(ChunkResidency.isDraining());
        ChunkResidency.report(12_000L, T0 + 4_000L);
        assertTrue(ChunkResidency.isDraining());
        ChunkResidency.report(5_100L, T0 + 5_000L);
        assertFalse("back to where we started -- the debt is paid", ChunkResidency.isDraining());
    }

    @Test
    public void drainGivesUpWhenNothingIsMovingBecauseTheRestIsNotOurs() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);

        // Falls a little, then stops: the remainder is pinned by players, spawn chunks or another mod.
        ChunkResidency.report(40_000L, T0 + 3_000L);
        // The stall clock runs from the last DROP (T0+3000), so it must pass 30 s beyond that, not
        // 30 s beyond the drain starting.
        ChunkResidency.report(40_000L, T0 + 32_000L);
        assertTrue("29 s of no movement is not yet a verdict", ChunkResidency.isDraining());
        ChunkResidency.report(40_000L, T0 + 33_001L);
        assertFalse("no further unload pass will move it, so stop arming one",
                ChunkResidency.isDraining());
    }

    @Test
    public void drainHasAnAbsoluteCeilingSoItCannotArmTheUnloadPassForEver() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(500_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);

        // Trickles downward for ever, one chunk at a time, always making "progress".
        long loaded = 500_000L;
        long now = T0 + 2_000L;
        while (now < T0 + 11 * 60_000L) {
            now += 5_000L;
            loaded -= 1L;
            ChunkResidency.report(loaded, now);
        }
        assertFalse("ten minutes is enough; something else is wrong", ChunkResidency.isDraining());
    }

    @Test
    public void startingANewRunClearsAnyOutstandingDrain() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        assertTrue(ChunkResidency.isDraining());

        ChunkResidency.noteTaskStart(T0 + 2_500L);
        assertFalse("the new run drives the unload pass itself", ChunkResidency.isDraining());
        assertEquals("and it rebaselines on what is there now", 45_000L, ChunkResidency.baseline());
    }
}
