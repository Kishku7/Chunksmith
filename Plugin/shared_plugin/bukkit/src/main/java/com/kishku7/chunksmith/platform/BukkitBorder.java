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

package com.kishku7.chunksmith.platform;

import org.bukkit.WorldBorder;
import com.kishku7.chunksmith.platform.util.Vector2;
import com.kishku7.chunksmith.shape.ShapeType;

public class BukkitBorder implements Border {
    WorldBorder worldBorder;

    public BukkitBorder(WorldBorder worldBorder) {
        this.worldBorder = worldBorder;
    }

    @Override
    public Vector2 getCenter() {
        return Vector2.of(worldBorder.getCenter().getX(), worldBorder.getCenter().getZ());
    }

    @Override
    public double getRadiusX() {
        return worldBorder.getSize() / 2d;
    }

    @Override
    public double getRadiusZ() {
        return worldBorder.getSize() / 2d;
    }

    @Override
    public String getShape() {
        return ShapeType.SQUARE;
    }
}