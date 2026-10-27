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

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Which dimensions the world-enter pregen covers (mod_support #52). */
public class WorldEnterDimensionsTest {

    private static final String O = WorldEnterDimensions.OVERWORLD_ID;
    private static final String N = WorldEnterDimensions.NETHER_ID;
    private static final String E = WorldEnterDimensions.END_ID;

    @Test
    public void theDefaultIsTheOverworldAlone() {
        assertEquals(List.of(O), WorldEnterDimensions.parse(WorldEnterDimensions.DEFAULT));
    }

    // Always overworld, nether, end -- whatever order they were typed in.
    @Test
    public void dimensionsRunInTheirOwnOrder() {
        assertEquals(List.of(O, N, E), WorldEnterDimensions.parse("end,nether,overworld"));
        assertEquals(List.of(N, E), WorldEnterDimensions.parse("end, nether"));
    }

    @Test
    public void allMeansAllThree() {
        assertEquals(List.of(O, N, E), WorldEnterDimensions.parse("all"));
        assertEquals("all", WorldEnterDimensions.spec(List.of(O, N, E)));
    }

    @Test
    public void caseSpacingAndRepeatsAreForgiven() {
        assertEquals(List.of(O, N), WorldEnterDimensions.parse(" Overworld , NETHER,nether "));
    }

    @Test
    public void anythingElseIsRefusedRatherThanHalfObeyed() {
        assertNull(WorldEnterDimensions.parse("overworld,moon"));
        assertNull(WorldEnterDimensions.parse(""));
        assertNull(WorldEnterDimensions.parse("   "));
        assertNull(WorldEnterDimensions.parse(null));
        assertNull(WorldEnterDimensions.parse("overworld,,end"));
    }

    @Test
    public void theCanonicalSpecIsWhatTheConfigFileHolds() {
        assertEquals("overworld", WorldEnterDimensions.spec(List.of(O)));
        assertEquals("overworld,nether", WorldEnterDimensions.spec(List.of(O, N)));
        assertEquals("nether,end", WorldEnterDimensions.spec(List.of(N, E)));
        assertEquals("end", WorldEnterDimensions.spec(List.of(E)));
    }

    // The overworld keeps its original file name so a world finished before 4.8.0 is still recognised.
    @Test
    public void theOverworldRecordKeepsItsOriginalFileName() {
        assertEquals("worldenter-done.json", WorldEnterDimensions.recordFileName(O));
        assertEquals("worldenter-done-nether.json", WorldEnterDimensions.recordFileName(N));
        assertEquals("worldenter-done-end.json", WorldEnterDimensions.recordFileName(E));
    }

    @Test
    public void theScreenNamesEachDimension() {
        assertEquals("the Overworld", WorldEnterDimensions.displayName(O));
        assertEquals("the Nether", WorldEnterDimensions.displayName(N));
        assertEquals("the End", WorldEnterDimensions.displayName(E));
    }
}
