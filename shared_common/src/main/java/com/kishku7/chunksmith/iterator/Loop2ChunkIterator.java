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

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.util.ChunkCoordinate;

import java.util.NoSuchElementException;

public class Loop2ChunkIterator implements ChunkIterator {
    private final int x1, x2, z1, z2;
    private final long diameterChunksZ;
    private final long total;
    private int x, z;
    private boolean hasNext = true;

    public Loop2ChunkIterator(Selection selection, long count) {
        this(selection);
        if (count <= 0) {
            return;
        }
        this.x = x1 + (int) (count / diameterChunksZ);
        this.z = z1 + (int) (count % diameterChunksZ);
        if (x > x2) {
            hasNext = false;
        }
    }

    public Loop2ChunkIterator(Selection selection) {
        int radiusChunksX = selection.radiusChunksX();
        int radiusChunksZ = selection.radiusChunksZ();
        int centerChunkX = selection.centerChunkX();
        int centerChunkZ = selection.centerChunkZ();
        this.x1 = centerChunkX - radiusChunksX;
        this.x2 = centerChunkX + radiusChunksX;
        this.z1 = centerChunkZ - radiusChunksZ;
        this.z2 = centerChunkZ + radiusChunksZ;
        this.x = x1;
        this.z = z1;
        int diameterChunksX = selection.diameterChunksX();
        this.diameterChunksZ = selection.diameterChunksZ();
        this.total = diameterChunksX * diameterChunksZ;
    }

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    @Override
    public ChunkCoordinate next() {
        if (!hasNext) {
            throw new NoSuchElementException();
        }
        ChunkCoordinate chunkCoord = new ChunkCoordinate(x, z);
        if (++z > z2) {
            z = z1;
            if (++x > x2) {
                hasNext = false;
            }
        }
        return chunkCoord;
    }

    @Override
    public long total() {
        return total;
    }

    @Override
    public String name() {
        return PatternType.LOOP;
    }
}
