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
