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

package com.kishku7.chunksmith.iterator;

import org.junit.Test;
import com.kishku7.chunksmith.Selection;

import static org.junit.Assert.assertEquals;

/**
 * This test checks to make sure the total number of chunks generated matches across iterators.
 */
public class TotalTest {
    private static final Selection.Builder SELECTION = Selection.builder(null, null).center(-25, 25).radius(50);

    /**
     * Checks that the totals still match when the radius is changed.
     */
    @Test
    public void radius() {
        Selection original = SELECTION.build();
        for (int i = 0; i < original.radiusX(); ++i) {
            Selection s = SELECTION.radiusX(i).radiusZ(i).build();
            ChunkIterator concentricIterator = new ConcentricChunkIterator(s);
            ChunkIterator loop2Iterator = new Loop2ChunkIterator(s);
            ChunkIterator spiralIterator = new SpiralChunkIterator(s);
            ChunkIterator regionIterator = new RegionChunkIterator(s);
            assertEquals(concentricIterator.total(), loop2Iterator.total());
            assertEquals(loop2Iterator.total(), spiralIterator.total());
            assertEquals(spiralIterator.total(), regionIterator.total());
        }
    }

    /**
     * Checks that the totals still match when the center is moved.
     */
    @Test
    public void center() {
        Selection original = SELECTION.build();
        for (int i = 0; i > original.centerX(); --i) {
            for (int j = 0; j < original.centerZ(); ++j) {
                Selection s = SELECTION.center(i, j).build();
                ChunkIterator concentricIterator = new ConcentricChunkIterator(s);
                ChunkIterator loop2Iterator = new Loop2ChunkIterator(s);
                ChunkIterator spiralIterator = new SpiralChunkIterator(s);
                ChunkIterator regionIterator = new RegionChunkIterator(s);
                assertEquals(concentricIterator.total(), loop2Iterator.total());
                assertEquals(loop2Iterator.total(), spiralIterator.total());
                assertEquals(spiralIterator.total(), regionIterator.total());
            }
        }
    }
}
