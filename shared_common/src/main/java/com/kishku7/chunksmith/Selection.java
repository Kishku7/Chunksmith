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

package com.kishku7.chunksmith;

import com.kishku7.chunksmith.iterator.PatternType;
import com.kishku7.chunksmith.platform.Border;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.platform.util.Vector2;
import com.kishku7.chunksmith.shape.ShapeType;
import com.kishku7.chunksmith.util.Parameter;

@SuppressWarnings("unused")
public final class Selection {
    public static final double DEFAULT_CENTER_X = 0d;
    public static final double DEFAULT_CENTER_Z = 0d;
    public static final double DEFAULT_RADIUS = 500d;
    private final Chunksmith chunky;
    private final World world;
    private final double centerX;
    private final double centerZ;
    private final double radiusX;
    private final double radiusZ;
    private final Parameter pattern;
    private final String shape;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radiusChunksX;
    private final int radiusChunksZ;
    private final int diameterChunksX;
    private final int diameterChunksZ;
    private final int centerRegionX;
    private final int centerRegionZ;
    private final int radiusRegionsX;
    private final int radiusRegionsZ;
    private final int diameterRegionsX;
    private final int diameterRegionsZ;

    private Selection(Chunksmith chunky, World world, double centerX, double centerZ, double radiusX, double radiusZ, Parameter pattern, String shape) {
        this.chunky = chunky;
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        this.pattern = pattern;
        this.shape = shape;
        this.centerChunkX = (int) centerX >> 4;
        this.centerChunkZ = (int) centerZ >> 4;
        this.radiusChunksX = (int) Math.ceil(radiusX / 16f);
        this.radiusChunksZ = (int) Math.ceil(radiusZ / 16f);
        this.diameterChunksX = 2 * radiusChunksX + 1;
        this.diameterChunksZ = 2 * radiusChunksZ + 1;
        this.centerRegionX = centerChunkX >> 5;
        this.centerRegionZ = centerChunkZ >> 5;
        this.radiusRegionsX = (int) Math.ceil(radiusChunksX / 32f);
        this.radiusRegionsZ = (int) Math.ceil(radiusChunksZ / 32f);
        this.diameterRegionsX = 2 * radiusRegionsX + 1;
        this.diameterRegionsZ = 2 * radiusRegionsZ + 1;
    }

    public static Builder builder(Chunksmith chunky, World world) {
        return new Builder(chunky, world);
    }

    public Chunksmith chunky() {
        return chunky;
    }

    public World world() {
        return this.world;
    }

    public double centerX() {
        return this.centerX;
    }

    public double centerZ() {
        return this.centerZ;
    }

    public double radiusX() {
        return this.radiusX;
    }

    public double radiusZ() {
        return this.radiusZ;
    }

    public Parameter pattern() {
        return this.pattern;
    }

    public String shape() {
        return this.shape;
    }

    public int centerChunkX() {
        return this.centerChunkX;
    }

    public int centerChunkZ() {
        return this.centerChunkZ;
    }

    public int radiusChunksX() {
        return this.radiusChunksX;
    }

    public int radiusChunksZ() {
        return this.radiusChunksZ;
    }

    public int diameterChunksX() {
        return this.diameterChunksX;
    }

    public int diameterChunksZ() {
        return this.diameterChunksZ;
    }

    public int centerRegionX() {
        return this.centerRegionX;
    }

    public int centerRegionZ() {
        return this.centerRegionZ;
    }

    public int radiusRegionsX() {
        return this.radiusRegionsX;
    }

    public int radiusRegionsZ() {
        return this.radiusRegionsZ;
    }

    public int diameterRegionsX() {
        return this.diameterRegionsX;
    }

    public int diameterRegionsZ() {
        return this.diameterRegionsZ;
    }

    public static final class Builder {
        private final Chunksmith chunky;
        private World world;
        private double centerX = DEFAULT_CENTER_X;
        private double centerZ = DEFAULT_CENTER_Z;
        private double radiusX = DEFAULT_RADIUS;
        private double radiusZ = DEFAULT_RADIUS;
        private Parameter pattern = Parameter.of(PatternType.REGION);
        private String shape = ShapeType.SQUARE;

        private Builder(Chunksmith chunky, World world) {
            this.chunky = chunky;
            this.world = world;
        }

        public Builder world(World world) {
            this.world = world;
            return this;
        }

        public Builder center(double centerX, double centerZ) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            return this;
        }

        public Builder centerX(double centerX) {
            this.centerX = centerX;
            return this;
        }

        public Builder centerZ(double centerZ) {
            this.centerZ = centerZ;
            return this;
        }

        public Builder radius(double radius) {
            this.radiusX = radius;
            this.radiusZ = radius;
            return this;
        }

        public Builder radiusX(double radiusX) {
            this.radiusX = radiusX;
            return this;
        }

        public Builder radiusZ(double radiusZ) {
            this.radiusZ = radiusZ;
            return this;
        }

        public Builder pattern(Parameter pattern) {
            this.pattern = pattern;
            return this;
        }

        public Builder shape(String shape) {
            this.shape = shape;
            return this;
        }

        public Builder spawn() {
            Location spawn = world.getSpawn();
            this.centerX = spawn.getX();
            this.centerZ = spawn.getZ();
            return this;
        }

        public Builder worldborder() {
            Border border = world.getWorldBorder();
            Vector2 center = border.getCenter();
            this.centerX = center.getX();
            this.centerZ = center.getZ();
            this.radiusX = border.getRadiusX();
            this.radiusZ = border.getRadiusZ();
            return this;
        }

        public Selection build() {
            return new Selection(chunky, world, centerX, centerZ, radiusX, radiusZ, pattern, shape);
        }
    }
}
