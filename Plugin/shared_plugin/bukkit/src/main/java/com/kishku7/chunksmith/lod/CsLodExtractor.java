package com.kishku7.chunksmith.lod;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bukkit-native counterpart to the Fabric/Forge/NeoForge CsLodExtractor (canonical algorithm:
 * _codegen/cog_sources/lod/CsLodExtractor.java). Produces the IDENTICAL neutral {@link CsLodChunk}
 * format from a Bukkit {@link ChunkSnapshot} instead of a live NMS LevelChunk, since this platform
 * has no mixin access to LevelChunkSection / LevelLightEngine. Every method used here (getBlockData,
 * getBlockSkyLight, getBlockEmittedLight, getBiome(x,y,z), World#getMinHeight/getMaxHeight,
 * BlockData#getAsString, Biome#getKey) was confirmed against the actual folia-api jar this cell
 * compiles against (javap, 2026-08-03) before writing this class -- not assumed.
 *
 * <p><b>Server-side generation ONLY (2026-08-03, mod_support #9 follow-up).</b> This platform does
 * not yet carry a renderer adapter or a client-streaming channel -- that is Chunksmith-Client's job
 * on Fabric/Forge/NeoForge and does not exist here yet. The store this produces is written to disk
 * and nothing else, deliberately, as a separate and later phase. See LodSupport (Bukkit).
 *
 * <p><b>Coordinates.</b> ChunkSnapshot's x/z are chunk-relative (0-15); y is WORLD-absolute (can be
 * negative on 1.18+ worlds), matching {@link World#getMinHeight()} / {@link World#getMaxHeight()}
 * (max is exclusive). Sections are always 16 blocks tall starting at a multiple of 16.
 *
 * <p><b>Biome sampling.</b> Bukkit exposes only a per-voxel {@code getBiome(x,y,z)} read, not
 * Minecraft's native 4x4x4 quantized storage. Sampling at each 4x4x4 cell's center reproduces the
 * same granularity the vanilla format actually carries, rather than paying for (and mildly
 * misrepresenting) a full per-voxel array.
 */
public final class CsLodExtractor {

    private CsLodExtractor() {
    }

    /** Extract, or null if the world has no vertical extent worth storing (should not happen). */
    public static CsLodChunk extract(final World world, final Chunk chunk) {
        final ChunkSnapshot snap = chunk.getChunkSnapshot(true, true, false);
        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight();
        final int minSectionY = Math.floorDiv(minY, 16);
        final int sectionCount = (maxY - minY) / 16;
        if (sectionCount <= 0) {
            return null;
        }

        final String dimension = world.getKey().toString();
        final int chunkX = chunk.getX();
        final int chunkZ = chunk.getZ();

        final Palette blocks = new Palette();
        final Palette biomes = new Palette();
        final List<CsLodChunk.Section> out = new ArrayList<>(sectionCount);

        for (int s = 0; s < sectionCount; s++) {
            final int sectionBaseY = minY + s * 16;
            out.add(extractSection(snap, sectionBaseY, blocks, biomes));
        }

        return new CsLodChunk(dimension, chunkX, chunkZ, minSectionY,
                blocks.entries(), biomes.entries(), out);
    }

    private static CsLodChunk.Section extractSection(final ChunkSnapshot snap, final int baseY,
                                                       final Palette blocks, final Palette biomes) {
        // ---- blocks (per voxel, YZX order, matching CsLodChunk.Section's documented layout) ----
        int[] blockIndices = new int[CsLodChunk.BLOCKS_PER_SECTION];
        int uniformBlock = -1;
        {
            int first = -1;
            boolean uniform = true;
            int n = 0;
            for (int y = 0; y < 16; y++) {
                final int wy = baseY + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockData data = snap.getBlockData(x, wy, z);
                        final int id = blocks.id(data.getAsString());
                        blockIndices[n++] = id;
                        if (first < 0) {
                            first = id;
                        } else if (id != first) {
                            uniform = false;
                        }
                    }
                }
            }
            if (uniform) {
                uniformBlock = first;
                blockIndices = null;
            }
        }

        // ---- biomes (4x4x4 cell-center sample -- see class note) ----
        int[] biomeIndices = new int[CsLodChunk.BIOMES_PER_SECTION];
        int uniformBiome = -1;
        {
            int first = -1;
            boolean uniform = true;
            int b = 0;
            for (int y = 0; y < 4; y++) {
                final int wy = baseY + y * 4 + 2;
                for (int z = 0; z < 4; z++) {
                    final int wz = z * 4 + 2;
                    for (int x = 0; x < 4; x++) {
                        final int wx = x * 4 + 2;
                        // 2026-08-02 (mod_support Bukkit-LOD 1.21.1 crash): Biome changed from a
                        // plain class to an interface across Paper API generations within the SAME
                        // 1.21.x compile line -- a jar built against one shape throws
                        // IncompatibleClassChangeError on a server running the other shape (observed:
                        // compiled shape vs Paper 1.21.1-133 runtime shape mismatched). Keyed has been
                        // a stable interface across every generation, and Biome has always implemented
                        // it, so dispatching getKey() through Keyed instead of Biome directly sidesteps
                        // the invokeinterface/invokevirtual mismatch regardless of which shape Biome is
                        // on this particular server build.
                        final org.bukkit.Keyed biome = snap.getBiome(wx, wy, wz);
                        final int id = biomes.id(biome.getKey().toString());
                        biomeIndices[b++] = id;
                        if (first < 0) {
                            first = id;
                        } else if (id != first) {
                            uniform = false;
                        }
                    }
                }
            }
            if (uniform) {
                uniformBiome = first;
                biomeIndices = null;
            }
        }

        // ---- light: sky and block, SEPARATE, present even for pure-air sections above terrain ----
        final Light sky = extractLight(snap, baseY, true);
        final Light block = extractLight(snap, baseY, false);

        return new CsLodChunk.Section(blockIndices, uniformBlock, biomeIndices, uniformBiome,
                sky.packed, sky.uniform, block.packed, block.uniform);
    }

    private static Light extractLight(final ChunkSnapshot snap, final int baseY, final boolean sky) {
        final byte[] packed = new byte[CsLodChunk.LIGHT_BYTES];
        int first = -1;
        boolean uniform = true;
        int n = 0;
        for (int y = 0; y < 16; y++) {
            final int wy = baseY + y;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int raw = sky ? snap.getBlockSkyLight(x, wy, z) : snap.getBlockEmittedLight(x, wy, z);
                    final int value = Math.max(0, Math.min(15, raw));
                    if (first < 0) {
                        first = value;
                    } else if (value != first) {
                        uniform = false;
                    }
                    if ((n & 1) == 0) {
                        packed[n >> 1] = (byte) value;
                    } else {
                        packed[n >> 1] |= (byte) (value << 4);
                    }
                    n++;
                }
            }
        }
        return uniform ? new Light(null, first) : new Light(packed, -1);
    }

    /** Either a packed 2048-byte array, or a single uniform nibble. Never both. */
    private static final class Light {
        private final byte[] packed;
        private final int uniform;

        private Light(final byte[] packed, final int uniform) {
            this.packed = packed;
            this.uniform = uniform;
        }
    }

    /** Insertion-ordered string palette. */
    private static final class Palette {
        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> order = new ArrayList<>();

        private int id(final String value) {
            final Integer existing = ids.get(value);
            if (existing != null) {
                return existing;
            }
            final int newId = order.size();
            ids.put(value, newId);
            order.add(value);
            return newId;
        }

        private List<String> entries() {
            return order;
        }
    }
}
