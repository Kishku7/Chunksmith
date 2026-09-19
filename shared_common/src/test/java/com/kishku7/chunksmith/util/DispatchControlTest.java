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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The dispatch controller's arithmetic (mod_support #33).
 *
 * <p>SCOPE, stated so nobody reads a green run as more than it is: these cases pin WHAT the
 * controller does once GenerationTask has decided the machine is overloaded or healthy. They do not
 * touch the sampling cadence, the tick-health decision itself, or the wiring in GenerationTask, and
 * they cannot tell you the curve is right on real hardware. Only a run on the reporter's class of
 * machine can do that.
 */
public class DispatchControlTest {

    /** What the reporter on mod_support #20 settled on by hand for a two-core client. */
    private static final int REPORTER_WIDTH = 16;

    @Test
    public void anIsolatedOverloadStepsDownByOne() {
        // One tick over budget is noise. Halving on noise throws away throughput that was fine.
        assertEquals(99, DispatchControl.reduce(100, false));
        assertEquals(49, DispatchControl.reduce(50, false));
    }

    @Test
    public void aSustainedOverloadHalves() {
        assertEquals(50, DispatchControl.reduce(100, true));
        assertEquals(8, DispatchControl.reduce(16, true));
    }

    @Test
    public void neitherPathEverReachesZero() {
        // A width of 0 dispatches nothing, forever -- a run that silently stopped.
        assertEquals(1, DispatchControl.reduce(1, true));
        assertEquals(1, DispatchControl.reduce(1, false));
        assertEquals(2, DispatchControl.reduce(2, true));
        assertEquals(2, DispatchControl.reduce(2, false));
    }

    @Test
    public void throttlingStopsAtAWidthThatStillDoesWork() {
        // Measured on a Paper server pinned to two cores: with no floor, halving from a ceiling of
        // 200 reached a width of 1 in about eight steps and the run then sat at 1-2 for minutes,
        // climbing back one per second. Two of five runs took twice as long as the best, and the
        // throttle notices reported width 1 ninety-two times. A width of 1 is not throttling, it
        // is stopping.
        assertEquals(DispatchControl.MIN_USEFUL_WIDTH, DispatchControl.reduce(16, true));
        assertEquals(DispatchControl.MIN_USEFUL_WIDTH, DispatchControl.reduce(9, false));
        assertEquals(DispatchControl.MIN_USEFUL_WIDTH, DispatchControl.reduce(10, true));
    }

    @Test
    public void aCollapseFromTheCeilingCannotRunAwayToOne() {
        // The whole failure in one assertion: halve repeatedly from the shipped ceiling and the
        // controller must come to rest somewhere it can still generate.
        int width = 200;
        for (int i = 0; i < 20; i++) {
            width = DispatchControl.reduce(width, true);
        }
        assertEquals("twenty sustained overloads must not leave the pipeline at 1",
                DispatchControl.MIN_USEFUL_WIDTH, width);
    }

    @Test
    public void halvingReachesAWorkableWidthInAHandfulOfStepsRatherThanEightyFour() {
        // This is the whole point. Additive decrease from the 100 a four-thread client gets by
        // default needs 84 samples at one per 250ms to arrive at 16 -- twenty-one seconds of the
        // machine being pinned. Multiplicative decrease has to get there in single figures.
        int width = 100;
        int steps = 0;
        while (width > REPORTER_WIDTH) {
            width = DispatchControl.reduce(width, true);
            steps++;
        }
        assertTrue("halving took " + steps + " steps to reach a workable width", steps <= 5);

        int additive = 100;
        int additiveSteps = 0;
        while (additive > REPORTER_WIDTH) {
            additive = DispatchControl.reduce(additive, false);
            additiveSteps++;
        }
        assertEquals("the old behaviour, kept here as the thing being fixed", 84, additiveSteps);
        assertTrue("and it stops at the floor rather than walking to 1",
                additive >= DispatchControl.MIN_USEFUL_WIDTH);
    }

    @Test
    public void burstsAreAllowedBelowTheKnownGoodWidthAndNotAtOrAboveIt() {
        assertTrue("returning to a width that already worked costs nothing",
                DispatchControl.mayBurst(10, 32));
        assertFalse("at the threshold the climb is a guess again",
                DispatchControl.mayBurst(32, 32));
        assertFalse(DispatchControl.mayBurst(40, 32));
    }

    @Test
    public void anUnconstrainedControllerStillBursts() {
        // Until something overloads, nothing is known against any width and a healthy server must
        // behave exactly as it did before this existed.
        assertTrue(DispatchControl.mayBurst(1, Integer.MAX_VALUE));
        assertTrue(DispatchControl.mayBurst(399, Integer.MAX_VALUE));
    }

    // ---- below the comfort floor: "some work is better than no work" (the owner, mod_support #33) ----

    /**
     * The floor is a COMFORT setting, not a reason to stop. Without permission the width parks at
     * MIN_USEFUL_WIDTH, which is right for an ordinary wobble -- a deeper collapse just buys a
     * long climb back. But once a good hard try at the floor has already failed, the alternative
     * is an auto-pause, and three chunks in flight beats nothing in flight.
     */
    @Test
    public void theFloorHoldsUntilPermissionIsGiven() {
        assertEquals("parked at the floor", DispatchControl.MIN_USEFUL_WIDTH,
                DispatchControl.reduce(DispatchControl.MIN_USEFUL_WIDTH, true));
        assertEquals("and the two-arg form must keep that behaviour",
                DispatchControl.MIN_USEFUL_WIDTH,
                DispatchControl.reduce(DispatchControl.MIN_USEFUL_WIDTH, true, false));
    }

    @Test
    public void belowTheFloorItWalksDownOneAtATime() {
        int width = DispatchControl.MIN_USEFUL_WIDTH;
        assertEquals(width - 1, DispatchControl.reduce(width, true, true));
        assertEquals("halving down here would throw away half the remaining throughput per step",
                width - 2, DispatchControl.reduce(width - 1, true, true));
    }

    @Test
    public void itNeverStopsGeneratingAltogether() {
        int width = DispatchControl.MIN_USEFUL_WIDTH;
        for (int i = 0; i < 50; i++) {
            width = DispatchControl.reduce(width, true, true);
        }
        assertEquals("a width of 0 is not a throttle, it is a stop -- that is auto-pause's job",
                DispatchControl.MIN_WORKING_WIDTH, width);
        assertTrue(width >= 1);
    }

    /** Above the floor, permission changes nothing: the normal halving still applies. */
    @Test
    public void permissionDoesNotDisturbTheNormalRange() {
        assertEquals(DispatchControl.reduce(100, true), DispatchControl.reduce(100, true, true));
        assertEquals(DispatchControl.reduce(100, false), DispatchControl.reduce(100, false, true));
    }
}
