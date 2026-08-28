package com.kishku7.chunksmith.lod;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.stream.Stream;

/**
 * Tests for the CSLOD presence bitmap that decides, per chunk, whether a pregen re-run has anything
 * left to do.
 *
 * <p>This is worth pinning down precisely because getting it wrong is silent in both directions. A
 * false "present" makes the pregen skip a chunk that has no LOD, and the hole never gets filled (the
 * exact bug this feature exists to kill). A false "absent" makes it reload and rewrite chunks that
 * were already done, which is the wasteful behaviour we are replacing. Neither shows up as an error.
 *
 * <p>The bitmap is read out of the region file header, so these tests write through the real
 * {@link CsLodRegionStore} and then read back through {@link CsLodPresenceIndex}. If the two ever
 * disagree about what a slot means, that is what fails here.
 */
public class CsLodPresenceIndexTest {

    @Test
    public void presentOnlyForWrittenChunks() throws Exception {
        Path root = Files.createTempDirectory("cslod-presence");
        try {
            CsLodRegionStore store = new CsLodRegionStore(root);
            try {
                store.write(sample(0, 0));
                store.write(sample(5, 9));
                // Negative coordinates: floorDiv/floorMod is where an off-by-one in the region and slot
                // arithmetic would hide, and it must agree with the store's own indexing exactly.
                store.write(sample(-1, -1));
                store.write(sample(-33, 40));
            } finally {
                store.close();
            }

            CsLodPresenceIndex index = new CsLodPresenceIndex(root);

            assertTrue("written chunk", index.hasLod(0, 0));
            assertTrue(index.hasLod(5, 9));
            assertTrue("negative coords", index.hasLod(-1, -1));
            assertTrue(index.hasLod(-33, 40));

            assertFalse("never written", index.hasLod(1, 0));
            assertFalse(index.hasLod(31, 31));
            assertFalse(index.hasLod(-2, -1));
        } finally {
            delete(root);
        }
    }

    @Test
    public void noStoreMeansAbsent() throws Exception {
        // The "LOD off, then turned on" case: chunks on disk, no CSLOD tree at all. Every chunk must
        // come back absent, so the re-run loads them and builds the LODs.
        Path root = Files.createTempDirectory("cslod-presence").resolve("never-created");
        CsLodPresenceIndex index = new CsLodPresenceIndex(root);

        assertFalse(index.hasLod(0, 0));
        assertFalse(index.hasLod(100, -100));
        assertEquals(0L, index.getHeaderBytesRead());
        assertFalse("query created the store", Files.exists(root));
    }

    @Test
    public void markLodIsVisibleBeforeTheWriteLands() throws Exception {
        // The same-run guarantee. The store writes asynchronously, so the on-disk header lags dispatch;
        // the in-memory bitmap is what stops a chunk generated early in a run from being re-processed
        // later in that same run.
        Path root = Files.createTempDirectory("cslod-presence");
        try {
            CsLodPresenceIndex index = new CsLodPresenceIndex(root);
            assertFalse(index.hasLod(7, 7));

            index.markLod(7, 7);

            assertTrue("marked chunk", index.hasLod(7, 7));
            assertFalse("neighbour was marked too", index.hasLod(8, 7));
            assertFalse("region file exists", Files.exists(root.resolve("r.0.0.cslod")));
        } finally {
            delete(root);
        }
    }

    @Test
    public void marksDoNotBleedAcrossRegions() throws Exception {
        Path root = Files.createTempDirectory("cslod-presence");
        try {
            CsLodPresenceIndex index = new CsLodPresenceIndex(root);
            // Same slot index (0) in four different regions: if the region key were ignored, these
            // would alias onto one bitmap and three of them would come back wrongly present.
            index.markLod(0, 0);

            assertTrue(index.hasLod(0, 0));
            assertFalse(index.hasLod(32, 0));
            assertFalse(index.hasLod(0, 32));
            assertFalse(index.hasLod(-32, -32));

            assertEquals(1, index.countInRegion(0, 0));
            assertEquals(0, index.countInRegion(1, 0));
        } finally {
            delete(root);
        }
    }

    @Test
    public void oneHeaderPerRegion() throws Exception {
        // The cost claim: presence for a whole region costs one 8 KB header read, not one read per
        // chunk. If this ever regresses to a per-chunk open, the check stops being free and the whole
        // design argument collapses, so it is asserted, not assumed.
        Path root = Files.createTempDirectory("cslod-presence");
        try {
            CsLodRegionStore store = new CsLodRegionStore(root);
            try {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        store.write(sample(x, z));
                    }
                }
            } finally {
                store.close();
            }

            CsLodPresenceIndex index = new CsLodPresenceIndex(root);
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    assertTrue(index.hasLod(x, z));
                }
            }

            assertEquals("1024 chunks in one region must cost exactly one header read",
                    1L, index.getRegionsLoaded());
            assertEquals(8192L, index.getHeaderBytesRead());
            assertEquals(1024L, index.getQueries());
            assertEquals(1024, index.countInRegion(0, 0));
        } finally {
            delete(root);
        }
    }

    @Test
    public void countsRecords() throws Exception {
        // Backs /cslod status: the number an operator compares against their chunk count.
        Path root = Files.createTempDirectory("cslod-presence");
        try {
            assertEquals(0L, CsLodPresenceIndex.countRecords(root));

            CsLodRegionStore store = new CsLodRegionStore(root);
            try {
                store.write(sample(0, 0));
                store.write(sample(1, 0));
                store.write(sample(-40, 70));   // a different region file
            } finally {
                store.close();
            }

            assertEquals(3L, CsLodPresenceIndex.countRecords(root));

            // Rewriting a chunk re-points its slot; it must not be double-counted.
            CsLodRegionStore again = new CsLodRegionStore(root);
            try {
                again.write(sample(0, 0));
            } finally {
                again.close();
            }
            assertEquals("a rewritten chunk is still one record", 3L, CsLodPresenceIndex.countRecords(root));
        } finally {
            delete(root);
        }
    }

    @Test
    public void truncatedRegionFileReadsAbsent() throws Exception {
        // A half-created region file must not blow up a pregen. Absent is the safe direction: we
        // rebuild LODs we may already have had, instead of skipping chunks that have none.
        Path root = Files.createTempDirectory("cslod-presence");
        try {
            Files.write(root.resolve("r.0.0.cslod"), new byte[64]);

            CsLodPresenceIndex index = new CsLodPresenceIndex(root);

            assertFalse(index.hasLod(0, 0));
            assertFalse(index.hasLod(20, 20));
            assertEquals(0L, CsLodPresenceIndex.countRecords(root));
        } finally {
            delete(root);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Returns the smallest valid record. The contents are irrelevant here; only the header slot matters. */
    private static CsLodChunk sample(int chunkX, int chunkZ) {
        CsLodChunk.Section uniform =
                new CsLodChunk.Section(null, 0, null, 0, null, 15, null, 0);
        return new CsLodChunk("minecraft:overworld", chunkX, chunkZ, -4,
                List.of("minecraft:air"),
                List.of("minecraft:plains"),
                List.of(uniform));
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
