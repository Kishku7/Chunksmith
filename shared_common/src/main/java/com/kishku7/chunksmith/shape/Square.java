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

import java.util.Arrays;
import java.util.List;

public class Square extends AbstractPolygon {
    double b1x, b1z, b2x, b2z;
    double p1x, p1z, p2x, p2z, p3x, p3z, p4x, p4z;

    protected Square(Selection selection, boolean chunkAligned) {
        super(selection, chunkAligned);
        this.b1x = centerX - radiusX;
        this.b1z = centerZ - radiusX;
        this.b2x = centerX + radiusX;
        this.b2z = centerZ + radiusX;
        this.p1x = centerX + radiusX;
        this.p1z = centerZ - radiusX;
        this.p2x = centerX - radiusX;
        this.p2z = centerZ - radiusX;
        this.p3x = centerX - radiusX;
        this.p3z = centerZ + radiusX;
        this.p4x = centerX + radiusX;
        this.p4z = centerZ + radiusX;
    }

    @Override
    public List<Vector2> points() {
        return Arrays.asList(
                Vector2.of(p1x, p1z),
                Vector2.of(p2x, p2z),
                Vector2.of(p3x, p3z),
                Vector2.of(p4x, p4z)
        );
    }

    @Override
    public boolean isBounding(double x, double z) {
        return x >= b1x && x <= b2x && z >= b1z && z <= b2z;
    }

    @Override
    public String name() {
        return ShapeType.SQUARE;
    }
}
