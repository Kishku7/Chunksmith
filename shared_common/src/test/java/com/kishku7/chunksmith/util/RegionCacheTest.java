/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.iterator.ChunkIterator;
import com.kishku7.chunksmith.iterator.ConcentricChunkIterator;

import static org.junit.Assert.assertTrue;

public class RegionCacheTest {
    @Test
    public void testChunkCache() {
        RegionCache regionCache = new RegionCache();
        Selection selection = Selection.builder(null, null).center(0, 0).radius(16).build();
        ChunkIterator iterator = new ConcentricChunkIterator(selection);
        RegionCache.WorldState worldState = regionCache.getWorld("world");
        iterator.forEachRemaining(chunk -> worldState.setGenerated(chunk.x(), chunk.z()));
        ChunkIterator iterator2 = new ConcentricChunkIterator(selection);
        iterator2.forEachRemaining(chunk -> assertTrue(worldState.isGenerated(chunk.x(), chunk.z())));
    }
}
