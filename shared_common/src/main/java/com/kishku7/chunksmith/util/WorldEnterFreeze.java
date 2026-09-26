/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
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

package com.kishku7.chunksmith.util;

/**
 * Whether the world-enter pregen is holding the world frozen, readable from code that must compile
 * on cells WITHOUT the world-enter feature (the LOD pipeline is on cells that are not).
 *
 * <p>{@code WorldEnterPregen} owns the freeze and mirrors it here; nothing else writes it. On a cell
 * without world-enter it simply stays false.
 */
public final class WorldEnterFreeze {

    private static volatile boolean frozen;

    private WorldEnterFreeze() {
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static void set(boolean value) {
        frozen = value;
    }
}
