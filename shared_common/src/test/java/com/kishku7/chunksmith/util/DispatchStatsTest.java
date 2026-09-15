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
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The dispatch readout (mod_support #32).
 *
 * <p>SCOPE: these pin the TEXT and the no-task/unknown states. They do not prove GenerationTask
 * publishes on the right cadence, or that the numbers are true -- only a run can show that.
 */
public class DispatchStatsTest {

    @After
    public void clear() {
        DispatchStats.clear();
    }

    @Test
    public void saysSoPlainlyWhenNothingIsRunning() {
        DispatchStats.clear();
        assertEquals("no generation task running", DispatchStats.describe());
    }

    @Test
    public void reportsTheWidthTheControllerSettledOn() {
        DispatchStats.publish(37, 40, 40, 100, 12);
        String line = DispatchStats.describe();
        assertTrue(line, line.contains("inFlight=37"));
        assertTrue(line, line.contains("width=40 of 100"));
        assertTrue(line, line.contains("rendererQueue=12"));
    }

    @Test
    public void anUnmovedThresholdReadsAsNoneYetRatherThanAsANumber() {
        // goodWidth starts AT the ceiling. Printing it as a figure would invite somebody to read a
        // threshold that has never been tested against anything as a measurement of their machine.
        DispatchStats.publish(0, 100, 100, 100, 0);
        assertTrue(DispatchStats.describe().contains("knownGood=none yet"));
    }

    @Test
    public void aThresholdThatHasMovedIsShown() {
        DispatchStats.publish(16, 16, 24, 100, 0);
        assertTrue(DispatchStats.describe().contains("knownGood=24"));
    }

    @Test
    public void aSinkThatCannotReportADepthIsNotPrintedAsZero() {
        // Zero means "the renderer is keeping up". No sink at all does not, and the two must never
        // read the same -- that conflation is what made a screenshot of this panel unanswerable.
        DispatchStats.publish(4, 8, 8, 64, -1);
        assertTrue(DispatchStats.describe().contains("rendererQueue=n/a"));
    }
}
