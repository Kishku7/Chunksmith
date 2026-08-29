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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Two things must never happen here, and both have already happened once in production.
 *
 * <p>An absent measurement must never read as an empty server: the throttle gates on this
 * number, and a caller that mistakes -1 for 0 opens the taps on exactly the server that could
 * not say how loaded it was.
 *
 * <p>And a finished run must keep owing its drain until the chunks are actually gone: 3.5.0
 * stopped driving the unload pass the moment a task ended and left an idle server holding the
 * backlog until it was restarted (see {@link ChunkResidency}). The drain state machine is
 * what stops that, so every one of its exits is tested against an injected clock rather than
 * a real one.
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
    public void unknownAtFirst() {
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
    public void zeroIsARealReading() {
        ChunkResidency.report(0L);
        assertEquals(0L, ChunkResidency.loadedChunks());
        assertTrue("zero is supported", ChunkResidency.isSupported());
    }

    @Test
    public void negativeIsIgnored() {
        ChunkResidency.report(1_234L);
        ChunkResidency.report(-1L);
        assertEquals("a negative must not erase a reading", 1_234L, ChunkResidency.loadedChunks());
    }

    @Test
    public void aStaleReadingIsUnknownNotZero() {
        ChunkResidency.report(5_000L, T0);
        assertEquals(5_000L, ChunkResidency.loadedChunksAt(T0 + ChunkResidency.FRESH_MILLIS));
        assertEquals(-1L, ChunkResidency.loadedChunksAt(T0 + ChunkResidency.FRESH_MILLIS + 1L));
    }

    @Test
    public void clearWipesIt() {
        ChunkResidency.report(1_234L);
        ChunkResidency.clear();
        assertEquals(-1L, ChunkResidency.loadedChunks());
        assertEquals(-1L, ChunkResidency.baseline());
        assertFalse(ChunkResidency.isDraining());
    }

    // --- the delta, which is what the gate actually reads --------------------------------------

    @Test
    public void addedChunksIsRelativeToTheBaseline() {
        ChunkResidency.report(18_000L);
        ChunkResidency.noteTaskStart();
        assertEquals(18_000L, ChunkResidency.baseline());
        ChunkResidency.report(25_000L);
        assertEquals("only OUR growth counts, not the server's own 18k", 7_000L, ChunkResidency.addedChunks());
    }

    @Test
    public void addedChunksNeverGoesNegative() {
        ChunkResidency.report(18_000L);
        ChunkResidency.noteTaskStart();
        ChunkResidency.report(9_000L);
        assertEquals(0L, ChunkResidency.addedChunks());
    }

    @Test
    public void addedChunksNeedsABaseline() {
        ChunkResidency.report(25_000L);
        assertEquals(-1L, ChunkResidency.addedChunks());
    }

    // --- the drain, whose whole job is to not be forgotten -------------------------------------

    @Test
    public void aFinishedRunOwesADrain() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        assertTrue("still draining after task end", ChunkResidency.isDraining());

        ChunkResidency.report(30_000L, T0 + 3_000L);
        assertTrue(ChunkResidency.isDraining());
        ChunkResidency.report(12_000L, T0 + 4_000L);
        assertTrue(ChunkResidency.isDraining());
        ChunkResidency.report(5_100L, T0 + 5_000L);
        assertFalse("back to baseline", ChunkResidency.isDraining());
    }

    @Test
    public void drainGivesUpWhenNothingIsMoving() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        ChunkResidency.noteDrainBudget(true);

        // Falls a little, then stops: the remainder is pinned by players, spawn chunks or another mod.
        ChunkResidency.report(40_000L, T0 + 3_000L);
        // The stall clock runs from the last DROP (T0+3000), so it must pass 30 s beyond that, not
        // 30 s beyond the drain starting.
        ChunkResidency.report(40_000L, T0 + 32_000L);
        assertTrue("29 s of no movement is not yet a verdict", ChunkResidency.isDraining());
        ChunkResidency.report(40_000L, T0 + 33_001L);
        assertFalse("a stalled drain gives up",
                ChunkResidency.isDraining());
    }

    @Test
    public void noStallVerdictWithoutABudget() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        ChunkResidency.noteDrainBudget(false);

        // Two full minutes of no movement, but it was never given a budget, so "it will not move"
        // is not a conclusion anybody is entitled to draw.
        for (long dt = 3_000L; dt <= 123_000L; dt += 5_000L) {
            ChunkResidency.report(45_000L, T0 + dt);
        }
        assertTrue("a starved budget is not a stall", ChunkResidency.isDraining());
    }

    @Test
    public void theLastPlayerLeavingRetriesAGivenUpDrain() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        ChunkResidency.noteDrainBudget(true);

        // Give up on a full budget: legitimate.
        ChunkResidency.report(45_000L, T0 + 3_000L);
        ChunkResidency.report(45_000L, T0 + 40_000L);
        assertFalse(ChunkResidency.isDraining());

        // Conditions change. A drain is not a one-shot.
        ChunkResidency.reconsiderDrain(T0 + 41_000L);
        assertTrue("still 40k above where the run started, so try again",
                ChunkResidency.isDraining());
    }

    @Test
    public void reconsiderIsANoOpAtBaseline() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(5_050L, T0 + 1_000L);
        ChunkResidency.reconsiderDrain(T0 + 2_000L);
        assertFalse("already at baseline",
                ChunkResidency.isDraining());
    }

    @Test
    public void drainHasATenMinuteCeiling() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(500_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        ChunkResidency.noteDrainBudget(true);

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
    public void describeSaysUnknownNotZero() {
        String snapshot = ChunkResidency.describe();
        assertTrue(snapshot, snapshot.contains("resident=unknown"));
        assertTrue(snapshot, snapshot.contains("baseline=unset"));
        assertTrue(snapshot, snapshot.contains("added=unknown"));
        assertTrue(snapshot, snapshot.contains("draining=false"));
    }

    @Test
    public void describeIsSafeToHandToAFormatter() {
        // Sender.sendMessagePrefixed runs its message through String.format. A literal percent sign in
        // this string is therefore a crash, and in 3.5.2 it was one: /cs debug answered "An unexpected
        // error occurred trying to execute that command". Unit-testing describe() in isolation did not
        // catch it, because the bug lived in the seam between describe() and the sender.
        ChunkResidency.report(1_000L);
        ChunkResidency.noteTaskStart();
        String snapshot = ChunkResidency.describe();
        assertFalse("no literal % may appear; see the class javadoc", snapshot.contains("%"));
        // The real proof: it survives the thing that actually happens to it.
        assertTrue(String.format(snapshot).length() > 0);
    }

    @Test
    public void describeNamesTheDrainOutcome() {
        ChunkResidency.report(5_000L, T0);
        ChunkResidency.noteTaskStart(T0);
        ChunkResidency.report(45_000L, T0 + 1_000L);
        ChunkResidency.noteTaskEnd(T0 + 2_000L);
        assertTrue(ChunkResidency.describe().contains("draining=true"));

        ChunkResidency.report(5_100L, T0 + 3_000L);
        String snapshot = ChunkResidency.describe();
        assertTrue(snapshot, snapshot.contains("draining=false"));
        assertTrue("describe names why it stopped",
                snapshot.contains("back to where the run started"));
        assertTrue("and how much it actually freed", snapshot.contains("39900 freed"));
    }

    @Test
    public void aNewRunClearsTheDrain() {
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
