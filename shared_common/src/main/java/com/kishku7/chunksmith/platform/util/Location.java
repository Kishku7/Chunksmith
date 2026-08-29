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

import com.kishku7.chunksmith.platform.World;

public class Location {
    private World world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public Location(World world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public Location(World world, double x, double y, double z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3 toVector() {
        return new Vector3(x, y, z);
    }

    public Location add(Vector3 vector) {
        x += vector.getX();
        y += vector.getY();
        z += vector.getZ();
        return this;
    }

    public Location setDirection(Vector3 direction) {
        double dirX = direction.getX();
        double dirY = direction.getY();
        double dirZ = direction.getZ();
        if (dirX == 0 && dirZ == 0) {
            if (dirY == 0) {
                pitch = 0;
            } else {
                pitch = dirY < 0 ? 90 : -90;
            }
            return this;
        }
        yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
        pitch = (float) Math.toDegrees(-Math.atan(dirY / Math.sqrt(dirX * dirX + dirZ * dirZ)));
        return this;
    }

    public World getWorld() {
        return world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }
}
