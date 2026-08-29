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

public class SpiralChunkIterator implements ChunkIterator {
    private static final int RIGHT = 0, DOWN = 1, LEFT = 2, UP = 3;
    private final int stopX, stopZ;
    private final long total;
    private int x, z;
    private int span = 1, spanCount, spanProgress;
    private int direction;
    private boolean hasNext = true;

    public SpiralChunkIterator(Selection selection, long count) {
        this(selection);
        if (count <= 0) {
            return;
        }
        int diameterFinished = (int) Math.floor(Math.sqrt(count));
        if (diameterFinished % 2 == 0) {
            --diameterFinished;
        }
        this.span = diameterFinished;
        this.spanCount = 1;
        this.direction = DOWN;
        int radiusFinished = diameterFinished / 2;
        this.x += radiusFinished + 1;
        this.z += radiusFinished;
        long perimeterCount = count - (long) diameterFinished * diameterFinished;
        int spanned;
        spanned = (int) Math.min(span, perimeterCount);
        this.z -= spanned;
        if (spanned == span) {
            ++span;
            spanCount = 0;
            direction = LEFT;
        } else {
            spanProgress += spanned;
        }
        perimeterCount -= spanned;
        spanned = (int) Math.min(span, perimeterCount);
        this.x -= spanned;
        if (spanned == span) {
            spanCount = 1;
            direction = UP;
        } else {
            spanProgress += spanned;
        }
        perimeterCount -= spanned;
        spanned = (int) Math.min(span, perimeterCount);
        this.z += spanned;
        if (spanned == span) {
            ++span;
            spanCount = 0;
            direction = RIGHT;
        } else {
            spanProgress += spanned;
        }
        perimeterCount -= spanned;
        spanned = (int) Math.min(span, perimeterCount);
        this.x += spanned;
        if (spanned == span) {
            spanCount = 1;
            direction = DOWN;
        } else {
            spanProgress += spanned;
        }
        if (x > stopX) {
            hasNext = false;
        }
    }

    public SpiralChunkIterator(Selection selection) {
        int radiusChunks = selection.radiusChunksX();
        this.x = selection.centerChunkX();
        this.z = selection.centerChunkZ();
        this.stopX = x + radiusChunks;
        this.stopZ = z + radiusChunks;
        long diameter = selection.diameterChunksX();
        this.total = diameter * diameter;
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
        if (x == stopX && z == stopZ) {
            hasNext = false;
        }
        if (spanCount == 2) {
            spanCount = 0;
            ++span;
        }
        if (direction == RIGHT) {
            x += 1;
        } else if (direction == DOWN) {
            z -= 1;
        } else if (direction == LEFT) {
            x -= 1;
        } else if (direction == UP) {
            z += 1;
        } else {
            throw new IllegalStateException("Invalid direction");
        }
        ++spanProgress;
        if (spanProgress == span) {
            spanProgress = 0;
            ++spanCount;
            direction = direction == UP ? RIGHT : ++direction;
        }
        return chunkCoord;
    }

    @Override
    public long total() {
        return total;
    }

    @Override
    public String name() {
        return PatternType.SPIRAL;
    }
}
