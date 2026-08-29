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
import com.kishku7.chunksmith.util.ChunkCoordinate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * This test compares the boundaries of the generated region for each
 * iterator to make sure they are consistent with one another.
 */
public class BoundaryTest {
    private static final Selection SELECTION = Selection.builder(null, null).center(-25, 25).radius(50).build();

    @Test
    public void boundaries() {
        ChunkIterator concentricIterator = new ConcentricChunkIterator(SELECTION);
        ChunkIterator loop2Iterator = new Loop2ChunkIterator(SELECTION);
        ChunkIterator spiralIterator = new SpiralChunkIterator(SELECTION);
        ChunkIterator regionIterator = new RegionChunkIterator(SELECTION);
        List<ChunkCoordinate> concentricCoordinates = new ArrayList<>();
        List<ChunkCoordinate> loop2Coordinates = new ArrayList<>();
        List<ChunkCoordinate> spiralCoordinates = new ArrayList<>();
        List<ChunkCoordinate> regionCoordinates = new ArrayList<>();
        concentricIterator.forEachRemaining(concentricCoordinates::add);
        loop2Iterator.forEachRemaining(loop2Coordinates::add);
        spiralIterator.forEachRemaining(spiralCoordinates::add);
        regionIterator.forEachRemaining(regionCoordinates::add);
        Collections.sort(concentricCoordinates);
        Collections.sort(loop2Coordinates);
        Collections.sort(spiralCoordinates);
        Collections.sort(regionCoordinates);
        ChunkCoordinate concentricPoint1 = concentricCoordinates.get(0);
        ChunkCoordinate concentricPoint2 = concentricCoordinates.get(concentricCoordinates.size() - 1);
        ChunkCoordinate loop2Point1 = loop2Coordinates.get(0);
        ChunkCoordinate loop2Point2 = loop2Coordinates.get(loop2Coordinates.size() - 1);
        ChunkCoordinate spiralPoint1 = spiralCoordinates.get(0);
        ChunkCoordinate spiralPoint2 = spiralCoordinates.get(spiralCoordinates.size() - 1);
        ChunkCoordinate regionPoint1 = regionCoordinates.get(0);
        ChunkCoordinate regionPoint2 = regionCoordinates.get(regionCoordinates.size() - 1);
        assertEquals(concentricPoint1.x(), loop2Point1.x());
        assertEquals(concentricPoint1.z(), loop2Point1.z());
        assertEquals(loop2Point1.x(), spiralPoint1.x());
        assertEquals(loop2Point1.z(), spiralPoint1.z());
        assertEquals(spiralPoint1.x(), regionPoint1.x());
        assertEquals(spiralPoint1.z(), regionPoint1.z());
        assertEquals(concentricPoint2.x(), loop2Point2.x());
        assertEquals(concentricPoint2.z(), loop2Point2.z());
        assertEquals(loop2Point2.x(), spiralPoint2.x());
        assertEquals(loop2Point2.z(), spiralPoint2.z());
        assertEquals(spiralPoint2.x(), regionPoint2.x());
        assertEquals(spiralPoint2.z(), regionPoint2.z());
    }
}
