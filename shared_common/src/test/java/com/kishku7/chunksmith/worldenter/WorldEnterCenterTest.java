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

package com.kishku7.chunksmith.worldenter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The world-enter pregen centre: parsing it, and what a completed run then satisfies.
 *
 * <p>mod_support #34. The two halves are tested together on purpose -- the reason the centre is
 * parseable at all is so a finished world can be asked whether it already covers a new one, and
 * that question is the half that can silently refuse somebody forever.
 */
public class WorldEnterCenterTest {

    @Test
    public void parsesTheTwoWords() {
        assertFalse(WorldEnterCenter.parse("origin").followsSpawn());
        assertEquals(0.0, WorldEnterCenter.parse("origin").x(), 0.0);
        assertTrue(WorldEnterCenter.parse("spawn").followsSpawn());
    }

    @Test
    public void isCaseAndSpaceInsensitive() {
        assertTrue(WorldEnterCenter.parse("  SPAWN ").followsSpawn());
        assertEquals("512,-64", WorldEnterCenter.parse(" 512 , -64 ").spec());
    }

    @Test
    public void parsesACoordinatePair() {
        WorldEnterCenter at = WorldEnterCenter.parse("512,-64");
        assertFalse(at.followsSpawn());
        assertEquals(512.0, at.x(), 0.0);
        assertEquals(-64.0, at.z(), 0.0);
    }

    /**
     * Refused, not coerced. A fallback here would let {@code /cs set} answer "done" while changing
     * nothing, which is the failure this codebase keeps paying for.
     */
    @Test
    public void refusesWhatIsNotACentre() {
        assertNull(WorldEnterCenter.parse(null));
        assertNull(WorldEnterCenter.parse(""));
        assertNull(WorldEnterCenter.parse("middle"));
        assertNull(WorldEnterCenter.parse("512"));          // half a pair is a typo
        assertNull(WorldEnterCenter.parse("512,"));
        assertNull(WorldEnterCenter.parse(",64"));
        assertNull(WorldEnterCenter.parse("1,2,3"));
        assertNull(WorldEnterCenter.parse("x,z"));
        assertNull(WorldEnterCenter.parse("NaN,0"));
    }

    /** Whole numbers read back without a decimal point, because an operator typed them that way. */
    @Test
    public void readsBackCanonically() {
        assertEquals("origin", WorldEnterCenter.parse("0,0").spec());
        assertEquals("spawn", WorldEnterCenter.spawn().spec());
        assertEquals("512,-64", WorldEnterCenter.at(512.0, -64.0).spec());
        assertEquals("origin", WorldEnterCenter.origin().spec());
    }

    // ---- what a completed run satisfies ------------------------------------

    private static WorldEnterDone done(long radius, double x, double z) {
        return new WorldEnterDone("minecraft:overworld", radius, x, z, 1L);
    }

    /**
     * A record written before 4.3.0 has no centre at all. Reading it as origin is not a default --
     * the pregen was hard-coded to (0, 0), so that is what those runs actually generated.
     */
    @Test
    public void aPre430RecordStillSatisfiesAnOriginRequest() {
        WorldEnterDone old = new WorldEnterDone("minecraft:overworld", 4096L, 1L);
        assertTrue(old.satisfies("minecraft:overworld", 4096L));
        assertTrue(old.satisfies("minecraft:overworld", 1024L));
        assertTrue(old.satisfies("minecraft:overworld", 4096L, 0.0, 0.0));
        assertFalse("a smaller finished run must not satisfy a bigger request",
                old.satisfies("minecraft:overworld", 8192L));
    }

    @Test
    public void aDifferentDimensionIsNeverSatisfied() {
        assertFalse(done(4096L, 0, 0).satisfies("minecraft:the_nether", 16L, 0.0, 0.0));
    }

    /**
     * Containment, not proximity: the wanted disc has to lie INSIDE the finished one. This is the
     * case that matters for #34 -- somebody moving the centre to spawn on a world already finished
     * around origin is asking for ground that was never generated.
     */
    @Test
    public void aMovedCentreIsOnlySatisfiedWhenTheNewDiscFitsInsideTheOldOne() {
        WorldEnterDone finished = done(4096L, 0.0, 0.0);
        assertTrue("well inside", finished.satisfies("minecraft:overworld", 1000L, 500.0, 0.0));
        assertTrue("exactly touching the edge",
                finished.satisfies("minecraft:overworld", 1096L, 3000.0, 0.0));
        assertFalse("one block too far out",
                finished.satisfies("minecraft:overworld", 1097L, 3000.0, 0.0));
        assertFalse("spawn 5000 out, nothing generated there",
                finished.satisfies("minecraft:overworld", 4096L, 5000.0, 0.0));
    }

    /** Distance is euclidean, so a diagonal offset counts as the hypotenuse, not per-axis. */
    @Test
    public void offsetIsMeasuredDiagonally() {
        WorldEnterDone finished = done(1000L, 0.0, 0.0);
        // (300, 400) is exactly 500 away; 500 + 500 = 1000 fits, 501 does not.
        assertTrue(finished.satisfies("minecraft:overworld", 500L, 300.0, 400.0));
        assertFalse(finished.satisfies("minecraft:overworld", 501L, 300.0, 400.0));
    }
}
