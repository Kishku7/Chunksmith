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

package com.kishku7.chunksmith;

public final class ChunksmithProvider {
    private static Chunksmith instance;

    private ChunksmithProvider() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    public static Chunksmith get() {
        if (instance == null) {
            throw new IllegalStateException("Chunksmith is not loaded.");
        }
        return instance;
    }

    public static boolean isLoaded() {
        return instance != null;
    }

    static void register(Chunksmith instance) {
        ChunksmithProvider.instance = instance;
    }

    static void unregister() {
        ChunksmithProvider.instance = null;
    }
}
