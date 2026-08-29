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

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.util.Vector2;

public abstract class AbstractShape implements Shape {
    protected final double centerX, centerZ;
    protected final double diameterX, diameterZ;
    protected final double radiusX, radiusZ;

    protected AbstractShape(Selection selection, boolean chunkAligned) {
        if (chunkAligned) {
            this.centerX = (double) (selection.centerChunkX() << 4) + 8;
            this.centerZ = (double) (selection.centerChunkZ() << 4) + 8;
            this.diameterX = selection.diameterChunksX() << 4;
            this.diameterZ = selection.diameterChunksZ() << 4;
            this.radiusX = diameterX / 2;
            this.radiusZ = diameterZ / 2;
        } else {
            this.centerX = selection.centerX();
            this.centerZ = selection.centerZ();
            this.radiusX = selection.radiusX();
            this.radiusZ = selection.radiusZ();
            this.diameterX = 2 * radiusX;
            this.diameterZ = 2 * radiusZ;
        }
    }

    public Vector2 center() {
        return Vector2.of(centerX, centerZ);
    }
}
