package com.kishku7.chunksmith.util;

import org.junit.Test;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.iterator.ChunkIterator;
import com.kishku7.chunksmith.iterator.ConcentricChunkIterator;

import static org.junit.Assert.assertTrue;

public class RegionCacheTest {
    @Test
    public void testChunkCache() {
        final RegionCache regionCache = new RegionCache();
        final Selection selection = Selection.builder(null, null).center(0, 0).radius(16).build();
        final ChunkIterator iterator = new ConcentricChunkIterator(selection);
        final RegionCache.WorldState worldState = regionCache.getWorld("world");
        iterator.forEachRemaining(chunk -> worldState.setGenerated(chunk.x(), chunk.z()));
        final ChunkIterator iterator2 = new ConcentricChunkIterator(selection);
        iterator2.forEachRemaining(chunk -> assertTrue(worldState.isGenerated(chunk.x(), chunk.z())));
    }
}
