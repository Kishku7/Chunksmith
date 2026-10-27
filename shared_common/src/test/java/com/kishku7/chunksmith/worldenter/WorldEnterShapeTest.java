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

/** The world-enter pregen shape (mod_support #51). */
public class WorldEnterShapeTest {

    @Test
    public void everyOfferedShapeParsesToItself() {
        for (String shape : WorldEnterShape.ALL) {
            assertEquals(shape, WorldEnterShape.parse(shape));
        }
    }

    @Test
    public void caseAndSpacingAreForgiven() {
        assertEquals("square", WorldEnterShape.parse("  SQUARE "));
        assertEquals("diamond", WorldEnterShape.parse("Diamond"));
    }

    // An ellipse is a circle and a rectangle a square until they get two radii, which this does not.
    @Test
    public void shapesThatNeedTwoRadiiAreNotOffered() {
        assertNull(WorldEnterShape.parse("ellipse"));
        assertNull(WorldEnterShape.parse("rectangle"));
        assertNull(WorldEnterShape.parse("oval"));
        assertNull(WorldEnterShape.parse(""));
        assertNull(WorldEnterShape.parse(null));
        assertNull(WorldEnterShape.parse("blob"));
    }

    @Test
    public void theDefaultIsTheCircleItAlwaysWas() {
        assertEquals("circle", WorldEnterShape.DEFAULT);
        assertEquals((long) Math.ceil(Math.PI * 256 * 256), WorldEnterShape.estimateChunks(null, 4096L));
    }

    @Test
    public void aSquareOfTheSameRadiusHoldsMoreThanACircleAndADiamondLess() {
        long circle = WorldEnterShape.estimateChunks("circle", 4096L);
        long square = WorldEnterShape.estimateChunks("square", 4096L);
        long diamond = WorldEnterShape.estimateChunks("diamond", 4096L);
        assertEquals(4L * 256 * 256, square);
        assertTrue(square > circle);
        assertTrue(circle > diamond);
        assertEquals(2L * 256 * 256, diamond);
    }

    // The reason the shape is recorded at all: a finished circle must not stop a square from running.
    @Test
    public void aCircleDoesNotCoverASquareButASquareCoversEverything() {
        assertFalse(WorldEnterShape.covers("circle", "square"));
        assertTrue(WorldEnterShape.covers("square", "circle"));
        assertTrue(WorldEnterShape.covers("square", "star"));
        assertTrue(WorldEnterShape.covers("hexagon", "hexagon"));
        assertFalse(WorldEnterShape.covers("hexagon", "circle"));
    }

    // A record from before 4.8.0 has no shape, and every one of those was a circle.
    @Test
    public void anAbsentShapeIsACircle() {
        assertTrue(WorldEnterShape.covers(null, "circle"));
        assertFalse(WorldEnterShape.covers(null, "square"));
    }
}
