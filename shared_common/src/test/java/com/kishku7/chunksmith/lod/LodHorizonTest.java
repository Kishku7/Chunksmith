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

package com.kishku7.chunksmith.lod;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LodHorizonTest {

    private static final String WORLD = "test:horizon";

    @After
    public void tearDown() {
        LodHorizon.release(WORLD);
    }

    @Test
    public void sealKeepsTwoChunksPastTheHorizonSaved() {
        // 160 blocks = 10 chunks; the seal saves 11 and 12; 13 is the first chunk dropped.
        LodHorizon.arm(WORLD, 0, 0, 160, false, (x, z) -> false);
        assertFalse(LodHorizon.inDiscardZone(WORLD, 10, 0));
        assertFalse(LodHorizon.inDiscardZone(WORLD, 12, 0));
        assertTrue(LodHorizon.inDiscardZone(WORLD, 13, 0));
        assertTrue(LodHorizon.inDiscardZone(WORLD, -13, 13));
        assertEquals(LodHorizon.isBeyond(0, 0, 160, false, 13, 0), LodHorizon.inDiscardZone(WORLD, 13, 0));
    }

    @Test
    public void squareUsesTheSquareAndCircleTheCircle() {
        LodHorizon.arm(WORLD, 0, 0, 160, false, (x, z) -> false);
        assertFalse(LodHorizon.inDiscardZone(WORLD, 12, 12));
        LodHorizon.arm(WORLD, 0, 0, 160, true, (x, z) -> false);
        assertTrue(LodHorizon.inDiscardZone(WORLD, 12, 12));
        assertFalse(LodHorizon.inDiscardZone(WORLD, 12, 0));
    }

    @Test
    public void existingTerrainIsNeverDropped() {
        LodHorizon.arm(WORLD, 0, 0, 160, false, (x, z) -> x == 20 && z == 0);
        assertFalse(LodHorizon.inDiscardZone(WORLD, 20, 0));
        assertTrue(LodHorizon.inDiscardZone(WORLD, 21, 0));
    }

    @Test
    public void aChunkAPlayerWasNearIsKept() {
        LodHorizon.arm(WORLD, 0, 0, 160, false, (x, z) -> false);
        LodHorizon.keepAround(WORLD, 30, 30, 2);
        assertFalse(LodHorizon.inDiscardZone(WORLD, 32, 28));
        assertTrue(LodHorizon.inDiscardZone(WORLD, 33, 30));
    }

    @Test
    public void nothingIsDroppedInAnotherWorldOrWhenDisarmed() {
        assertFalse(LodHorizon.inDiscardZone("test:other", 100, 100));
        LodHorizon.arm(WORLD, 0, 0, 160, false, (x, z) -> false);
        assertFalse(LodHorizon.inDiscardZone("test:other", 100, 100));
        assertTrue(LodHorizon.isArmed(WORLD));
    }

    @Test
    public void theCentreIsTheTaskCentreNotTheOrigin() {
        LodHorizon.arm(WORLD, 100, -50, 160, false, (x, z) -> false);
        assertFalse(LodHorizon.inDiscardZone(WORLD, 112, -50));
        assertTrue(LodHorizon.inDiscardZone(WORLD, 113, -50));
        assertTrue(LodHorizon.inDiscardZone(WORLD, 0, 0));
    }
}
