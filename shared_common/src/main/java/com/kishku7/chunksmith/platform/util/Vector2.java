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

package com.kishku7.chunksmith.platform.util;

public class Vector2 {
    private double x;
    private double z;

    public Vector2(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public static Vector2 of(double x, double z) {
        return new Vector2(x, z);
    }

    public Vector2 add(Vector2 other) {
        x += other.x;
        z += other.z;
        return this;
    }

    public Vector2 multiply(double value) {
        x *= value;
        z *= value;
        return this;
    }

    public Vector2 normalize() {
        double length = length();
        x /= length;
        z /= length;
        return this;
    }

    public double distance(Vector2 other) {
        return Math.sqrt(distanceSquared(other));
    }

    public double distanceSquared(Vector2 other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return dx * dx + dz * dz;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double lengthSquared() {
        return x * x + z * z;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }
}
