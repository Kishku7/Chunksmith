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

import static com.kishku7.chunksmith.shape.ShapeUtil.insideLine;

public class Hexagon extends AbstractPolygon {
    private final double p1x, p1z, p2x, p2z, p3x, p3z, p4x, p4z, p5x, p5z, p6x, p6z;

    public Hexagon(Selection selection, boolean chunkAligned) {
        super(selection, chunkAligned);
        this.p1x = centerX + radiusX * Math.cos(Math.toRadians(60));
        this.p1z = centerZ + radiusX * Math.sin(Math.toRadians(60));
        this.p2x = centerX + radiusX * Math.cos(Math.toRadians(120));
        this.p2z = centerZ + radiusX * Math.sin(Math.toRadians(120));
        this.p3x = centerX + radiusX * Math.cos(Math.toRadians(180));
        this.p3z = centerZ + radiusX * Math.sin(Math.toRadians(180));
        this.p4x = centerX + radiusX * Math.cos(Math.toRadians(240));
        this.p4z = centerZ + radiusX * Math.sin(Math.toRadians(240));
        this.p5x = centerX + radiusX * Math.cos(Math.toRadians(300));
        this.p5z = centerZ + radiusX * Math.sin(Math.toRadians(300));
        this.p6x = centerX + radiusX * Math.cos(Math.toRadians(360));
        this.p6z = centerZ + radiusX * Math.sin(Math.toRadians(360));
    }

    @Override
    public List<Vector2> points() {
        return Arrays.asList(
                Vector2.of(p1x, p1z),
                Vector2.of(p2x, p2z),
                Vector2.of(p3x, p3z),
                Vector2.of(p4x, p4z),
                Vector2.of(p5x, p5z),
                Vector2.of(p6x, p6z)
        );
    }

    @Override
    public boolean isBounding(double x, double z) {
        boolean inside12 = insideLine(p1x, p1z, p2x, p2z, x, z);
        boolean inside23 = insideLine(p2x, p2z, p3x, p3z, x, z);
        boolean inside34 = insideLine(p3x, p3z, p4x, p4z, x, z);
        boolean inside45 = insideLine(p4x, p4z, p5x, p5z, x, z);
        boolean inside56 = insideLine(p5x, p5z, p6x, p6z, x, z);
        boolean inside61 = insideLine(p6x, p6z, p1x, p1z, x, z);
        return inside12 && inside23 && inside34 && inside45 && inside56 && inside61;
    }

    @Override
    public String name() {
        return ShapeType.HEXAGON;
    }
}
