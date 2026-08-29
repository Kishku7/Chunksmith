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

package com.kishku7.chunksmith.shape;

import org.junit.Test;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.iterator.ChunkIterator;
import com.kishku7.chunksmith.iterator.ChunkIteratorFactory;
import com.kishku7.chunksmith.util.ChunkCoordinate;

import static org.junit.Assert.assertEquals;

/**
 * This test checks each shape to ensure that the number of chunks they generate is correct.
 */
public class ShapeTest {
    private static final Selection.Builder SELECTION = Selection.builder(null, null).center(-500, 500).radiusX(1000).radiusZ(500);

    @Test
    public void square() {
        testShape("square", 16129);
    }

    @Test
    public void circle() {
        testShape("circle", 12645);
    }

    @Test
    public void triangle() {
        testShape("triangle", 8065);
    }

    @Test
    public void diamond() {
        testShape("diamond", 8065);
    }

    @Test
    public void pentagon() {
        testShape("pentagon", 9593);
    }

    @Test
    public void star() {
        testShape("star", 4518);
    }

    @Test
    public void rectangle() {
        testShape("rectangle", 8255);
    }

    @Test
    public void ellipse() {
        testShape("ellipse", 6503);
    }

    private void testShape(String type, int expected) {
        Selection s = SELECTION.shape(type).build();
        ChunkIterator chunkIterator = ChunkIteratorFactory.getChunkIterator(s);
        Shape shape = ShapeFactory.getShape(s);
        int generated = 0;
        while (chunkIterator.hasNext()) {
            ChunkCoordinate chunkCoordinate = chunkIterator.next();
            int xChunkCenter = (chunkCoordinate.x() << 4) + 8;
            int zChunkCenter = (chunkCoordinate.z() << 4) + 8;
            if (shape.isBounding(xChunkCenter, zChunkCenter)) {
                ++generated;
            }
        }
        assertEquals(expected, generated);
    }
}
