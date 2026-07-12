package com.kishku7.chunksmith.lod;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Round-trip tests for the CSLOD format.
 *
 * <p>The format is the DISK format is the WIRE format: the same bytes are written into the region store,
 * served over the HTTP backchannel, dripped through the in-band fallback, and decoded by a completely
 * separate mod (Chunksmith-Client). Nothing else in the codebase pins that contract down, so an encoding
 * bug would surface only as corrupt terrain on somebody else's screen.
 *
 * <p>Both shapes of section are exercised on purpose: the DENSE one (explicit per-voxel arrays) and the
 * UNIFORM one (a single palette index standing in for 4096 voxels) -- the uniform path is what makes
 * carrying light to the build ceiling affordable, and it is the one with something to get wrong.
 */
public class CsLodCodecTest {

    @Test
    public void codecRoundTripsDenseAndUniformSections() throws Exception {
        final CsLodChunk original = sample("minecraft:overworld", 4, -7);

        final CsLodChunk decoded = CsLodCodec.decode(CsLodCodec.encode(original));

        assertChunkEquals(original, decoded);
    }

    @Test
    public void regionStoreReadsBackWhatItWrote() throws Exception {
        final Path root = Files.createTempDirectory("cslod-test");
        try {
            // Two chunks in the SAME region file, one of them at a negative coordinate -- the region/chunk
            // index arithmetic is where an off-by-one would hide.
            final CsLodChunk first = sample("minecraft:overworld", 0, 0);
            final CsLodChunk second = sample("minecraft:overworld", -1, 5);

            final CsLodRegionStore store = new CsLodRegionStore(root);
            try {
                store.write(first);
                store.write(second);
            } finally {
                store.close();
            }

            final CsLodRegionStore reopened = new CsLodRegionStore(root);
            try {
                assertChunkEquals(first, reopened.read(0, 0));
                assertChunkEquals(second, reopened.read(-1, 5));
                assertNull("a chunk never written must read back as null", reopened.read(9, 9));
            } finally {
                reopened.close();
            }

            final List<String> visited = new ArrayList<>();
            final int count = CsLodRegionStore.forEachChunk(root, chunk ->
                    visited.add(chunk.getChunkX() + "," + chunk.getChunkZ()));
            assertEquals(2, count);
            visited.sort(Comparator.naturalOrder());
            assertEquals(List.of("-1,5", "0,0"), visited);
        } finally {
            delete(root);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A chunk with one DENSE section and one UNIFORM section. */
    private static CsLodChunk sample(final String dimension, final int chunkX, final int chunkZ) {
        final int[] blocks = new int[CsLodChunk.BLOCKS_PER_SECTION];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = i % 3;
        }
        final int[] biomes = new int[CsLodChunk.BIOMES_PER_SECTION];
        for (int i = 0; i < biomes.length; i++) {
            biomes[i] = i % 2;
        }
        final byte[] sky = new byte[CsLodChunk.LIGHT_BYTES];
        final byte[] block = new byte[CsLodChunk.LIGHT_BYTES];
        for (int i = 0; i < CsLodChunk.LIGHT_BYTES; i++) {
            sky[i] = (byte) (i & 0xFF);
            block[i] = (byte) ((i * 7) & 0xFF);
        }

        final CsLodChunk.Section dense =
                new CsLodChunk.Section(blocks, -1, biomes, -1, sky, -1, block, -1);
        // Everything above the terrain: uniform air, uniform sky light, no block light.
        final CsLodChunk.Section uniform =
                new CsLodChunk.Section(null, 0, null, 0, null, 15, null, 0);

        return new CsLodChunk(dimension, chunkX, chunkZ, -4,
                List.of("minecraft:air", "minecraft:stone", "minecraft:water[level=0]"),
                List.of("minecraft:plains", "minecraft:river"),
                List.of(dense, uniform));
    }

    private static void assertChunkEquals(final CsLodChunk expected, final CsLodChunk actual) {
        assertNotNull("chunk did not survive the round trip", actual);
        assertEquals(expected.getDimension(), actual.getDimension());
        assertEquals(expected.getChunkX(), actual.getChunkX());
        assertEquals(expected.getChunkZ(), actual.getChunkZ());
        assertEquals(expected.getMinSectionY(), actual.getMinSectionY());
        assertEquals(expected.getBlockPalette(), actual.getBlockPalette());
        assertEquals(expected.getBiomePalette(), actual.getBiomePalette());
        assertEquals(expected.getSections().size(), actual.getSections().size());

        for (int i = 0; i < expected.getSections().size(); i++) {
            final CsLodChunk.Section want = expected.getSections().get(i);
            final CsLodChunk.Section got = actual.getSections().get(i);
            assertArrayEquals("section " + i + " blocks", want.getBlocks(), got.getBlocks());
            assertEquals("section " + i + " uniform block", want.getUniformBlock(), got.getUniformBlock());
            assertArrayEquals("section " + i + " biomes", want.getBiomes(), got.getBiomes());
            assertEquals("section " + i + " uniform biome", want.getUniformBiome(), got.getUniformBiome());
            assertArrayEquals("section " + i + " sky light", want.getSkyLight(), got.getSkyLight());
            assertEquals("section " + i + " uniform sky", want.getUniformSky(), got.getUniformSky());
            assertArrayEquals("section " + i + " block light", want.getBlockLight(), got.getBlockLight());
            assertEquals("section " + i + " uniform block light",
                    want.getUniformBlockLight(), got.getUniformBlockLight());
        }
    }

    private static void delete(final Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final Exception ignored) {
                    // Temp dir cleanup. A leftover file in the OS temp dir is not worth failing a test over.
                }
            });
        }
    }
}
