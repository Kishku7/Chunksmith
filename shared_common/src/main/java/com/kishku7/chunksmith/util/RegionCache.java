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

package com.kishku7.chunksmith.util;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegionCache {
    private final Map<String, WorldState> cache = new ConcurrentHashMap<>();

    public WorldState getWorld(String world) {
        return cache.computeIfAbsent(world, x -> new WorldState());
    }

    public void clear(String world) {
        cache.remove(world);
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public static final class WorldState {
        private final Map<Long, BitSet> regions = new ConcurrentHashMap<>();

        public void setGenerated(int x, int z) {
            int regionX = x >> 5;
            int regionZ = z >> 5;
            long regionKey = ChunkMath.pack(regionX, regionZ);
            BitSet region = regions.computeIfAbsent(regionKey, v -> new BitSet());
            int chunkIndex = ChunkMath.regionIndex(x, z);
            synchronized (region) {
                region.set(chunkIndex);
            }
        }

        public boolean isGenerated(int x, int z) {
            int regionX = x >> 5;
            int regionZ = z >> 5;
            long regionKey = ChunkMath.pack(regionX, regionZ);
            if (!regions.containsKey(regionKey)) {
                return false;
            }
            BitSet region = regions.get(regionKey);
            int chunkIndex = ChunkMath.regionIndex(x, z);
            synchronized (region) {
                return region.get(chunkIndex);
            }
        }
    }
}
