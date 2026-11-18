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

package com.kishku7.chunksmith.lod.xaero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XaeroTargetTest {

    @Test
    public void dimensionFoldersFollowWorldMapsNaming() {
        assertEquals("null", XaeroTarget.folderName("minecraft_overworld"));
        assertEquals("DIM-1", XaeroTarget.folderName("minecraft_the_nether"));
        assertEquals("DIM1", XaeroTarget.folderName("minecraft_the_end"));
        assertEquals("mymod$deep_dark", XaeroTarget.folderName("mymod_deep_dark"));
    }

    @Test
    public void theNetherIsLeftAloneUntilCaveLayersAreWritten() {
        assertFalse(XaeroTarget.supportsDimension("minecraft_the_nether"));
        assertTrue(XaeroTarget.supportsDimension("minecraft_overworld"));
        assertTrue(XaeroTarget.supportsDimension("minecraft_the_end"));
    }

    @Test
    public void withNoWorldMapLoadedNothingIsAvailableAndNothingThrows() {
        assertFalse(XaeroTarget.available());
    }
}
