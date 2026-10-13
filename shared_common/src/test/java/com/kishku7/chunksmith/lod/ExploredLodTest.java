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

public class ExploredLodTest {

    @After
    public void tearDown() {
        ExploredLod.reset();
    }

    @Test
    public void takesOnlyANewFullDirtyChunkWithEverythingOn() {
        assertTrue(ExploredLod.shouldTake(true, true, true, true, false));
    }

    @Test
    public void eachConditionAloneSaysNo() {
        assertFalse("setting off", ExploredLod.shouldTake(false, true, true, true, false));
        assertFalse("LOD off for the server", ExploredLod.shouldTake(true, false, true, true, false));
        assertFalse("proto chunk", ExploredLod.shouldTake(true, true, false, true, false));
        assertFalse("nothing new to save", ExploredLod.shouldTake(true, true, true, false, false));
        assertFalse("the store already holds it", ExploredLod.shouldTake(true, true, true, true, true));
    }

    @Test
    public void statusCountsAndSaysWhenOff() {
        ExploredLod.noteTaken();
        ExploredLod.noteTaken();
        ExploredLod.noteHeld();
        assertEquals("2 chunks added since start, 1 already in the store", ExploredLod.describe(true));
        ExploredLod.noteFailed();
        assertTrue(ExploredLod.describe(true).endsWith(", 1 failed"));
        assertEquals("off (lodFromExploredChunks false)", ExploredLod.describe(false));
    }
}
