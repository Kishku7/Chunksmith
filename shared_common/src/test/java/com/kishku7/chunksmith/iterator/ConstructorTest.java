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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * This test compares the default and continuation constructors of chunk iterator to
 * ensure that the results they return are consistent with each other.
 */
public class ConstructorTest {
    private static final Selection SELECTION = Selection.builder(null, null).center(-25, 25).radiusX(50).build();

    @Test
    public void concentric() {
        List<ChunkCoordinate> chunks = new ArrayList<>();
        ChunkIterator chunkIterator = new ConcentricChunkIterator(SELECTION);
        chunkIterator.forEachRemaining(chunks::add);
        int total = (int) chunkIterator.total();
        for (int i = 0; i < total; ++i) {
            List<ChunkCoordinate> continueChunks = new ArrayList<>();
            ChunkIterator continueIterator = new ConcentricChunkIterator(SELECTION, i);
            continueIterator.forEachRemaining(continueChunks::add);
            int continueTotal = (int) continueIterator.total();
            assertEquals("Total", total, continueTotal);
            int size = chunks.size(), continueSize = continueChunks.size();
            assertEquals("Continued Size", size - i, continueSize);
            for (int j = 0; j < continueSize; ++j) {
                int chunkX = chunks.get(j + i).x(), chunkZ = chunks.get(j + i).z();
                int continueChunkX = continueChunks.get(j).x(), continueChunkZ = continueChunks.get(j).z();
                assertTrue(chunkX == continueChunkX && chunkZ == continueChunkZ);
            }
        }
    }

    @Test
    public void loop2() {
        List<ChunkCoordinate> chunks = new ArrayList<>();
        ChunkIterator chunkIterator = new Loop2ChunkIterator(SELECTION);
        chunkIterator.forEachRemaining(chunks::add);
        int total = (int) chunkIterator.total();
        for (int i = 0; i < total; ++i) {
            List<ChunkCoordinate> continueChunks = new ArrayList<>();
            ChunkIterator continueIterator = new Loop2ChunkIterator(SELECTION, i);
            continueIterator.forEachRemaining(continueChunks::add);
            int continueTotal = (int) continueIterator.total();
            assertEquals("Total", total, continueTotal);
            int size = chunks.size(), continueSize = continueChunks.size();
            assertEquals("Continued Size", size - i, continueSize);
            for (int j = 0; j < continueSize; ++j) {
                int chunkX = chunks.get(j + i).x(), chunkZ = chunks.get(j + i).z();
                int continueChunkX = continueChunks.get(j).x(), continueChunkZ = continueChunks.get(j).z();
                assertTrue(chunkX == continueChunkX && chunkZ == continueChunkZ);
            }
        }
    }

    @Test
    public void spiral() {
        List<ChunkCoordinate> chunks = new ArrayList<>();
        ChunkIterator chunkIterator = new SpiralChunkIterator(SELECTION);
        chunkIterator.forEachRemaining(chunks::add);
        int total = (int) chunkIterator.total();
        for (int i = 0; i < total; ++i) {
            List<ChunkCoordinate> continueChunks = new ArrayList<>();
            ChunkIterator continueIterator = new SpiralChunkIterator(SELECTION, i);
            continueIterator.forEachRemaining(continueChunks::add);
            int continueTotal = (int) continueIterator.total();
            assertEquals("Total", total, continueTotal);
            int size = chunks.size(), continueSize = continueChunks.size();
            assertEquals("Continued Size", size - i, continueSize);
            for (int j = 0; j < continueSize; ++j) {
                int chunkX = chunks.get(j + i).x(), chunkZ = chunks.get(j + i).z();
                int continueChunkX = continueChunks.get(j).x(), continueChunkZ = continueChunks.get(j).z();
                assertTrue(chunkX == continueChunkX && chunkZ == continueChunkZ);
            }
        }
    }

    @Test
    public void region() {
        List<ChunkCoordinate> chunks = new ArrayList<>();
        ChunkIterator chunkIterator = new RegionChunkIterator(SELECTION);
        chunkIterator.forEachRemaining(chunks::add);
        int total = (int) chunkIterator.total();
        for (int i = 0; i < total; ++i) {
            List<ChunkCoordinate> continueChunks = new ArrayList<>();
            ChunkIterator continueIterator = new RegionChunkIterator(SELECTION, i);
            continueIterator.forEachRemaining(continueChunks::add);
            int continueTotal = (int) continueIterator.total();
            assertEquals("Total", total, continueTotal);
            int size = chunks.size(), continueSize = continueChunks.size();
            assertEquals("Continued Size", size - i, continueSize);
            for (int j = 0; j < continueSize; ++j) {
                int chunkX = chunks.get(j + i).x(), chunkZ = chunks.get(j + i).z();
                int continueChunkX = continueChunks.get(j).x(), continueChunkZ = continueChunks.get(j).z();
                assertTrue(chunkX == continueChunkX && chunkZ == continueChunkZ);
            }
        }
    }

    @Test
    public void rectangle() {
        List<ChunkCoordinate> chunks = new ArrayList<>();
        Selection s = Selection.builder(null, null).center(-25, 25).radiusX(50).radiusZ(100).build();
        ChunkIterator chunkIterator = new Loop2ChunkIterator(s);
        chunkIterator.forEachRemaining(chunks::add);
        int total = (int) chunkIterator.total();
        for (int i = 0; i < total; ++i) {
            List<ChunkCoordinate> continueChunks = new ArrayList<>();
            ChunkIterator continueIterator = new Loop2ChunkIterator(s, i);
            continueIterator.forEachRemaining(continueChunks::add);
            int continueTotal = (int) continueIterator.total();
            assertEquals("Total", total, continueTotal);
            int size = chunks.size(), continueSize = continueChunks.size();
            assertEquals("Continued Size", size - i, continueSize);
            for (int j = 0; j < continueSize; ++j) {
                int chunkX = chunks.get(j + i).x(), chunkZ = chunks.get(j + i).z();
                int continueChunkX = continueChunks.get(j).x(), continueChunkZ = continueChunks.get(j).z();
                assertTrue(chunkX == continueChunkX && chunkZ == continueChunkZ);
            }
        }
    }
}
