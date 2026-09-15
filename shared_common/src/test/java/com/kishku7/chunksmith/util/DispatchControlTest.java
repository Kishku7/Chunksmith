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
        assertEquals(1, DispatchControl.reduce(2, true));
        assertEquals(1, DispatchControl.reduce(2, false));
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
}
