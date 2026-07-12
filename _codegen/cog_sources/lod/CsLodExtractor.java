package com.kishku7.chunksmith.lod;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a live {@link LevelChunk} into a neutral {@link CsLodChunk}.
 *
 * <p>Runs on the SERVER MAIN THREAD, inside the generation hook, while the chunk is still
 * ticket-pinned -- it is the only moment the data exists. Everything it produces is plain arrays and
 * strings, so the rest of the pipeline (codec, store, wire) never touches a Minecraft type.
 *
 * <p><b>Light.</b> Sky and block light are kept SEPARATE (voxy blends them; Distant Horizons will
 * not), and light is captured for AIR voxels all the way to the build ceiling, because DH renders
 * unlit LODs black otherwise. Where the light engine has no {@link DataLayer} for a section, the
 * section's light is uniform by construction (nothing has been lit there), so we sample one position
 * through the layer listener and store a single uniform nibble -- which is also what makes carrying
 * sky light to the ceiling nearly free.
 *
 * <p><b>Block states, not block ids.</b> DH has no fluid channel -- water IS a state -- and
 * waterlogging, snow layers and stair shapes all change what a LOD should look like. We serialize
 * the full state via {@link BlockStateParser#serialize(BlockState)}, which is exactly the string form
 * DH's wrapper factory consumes.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodExtractor {

    private CsLodExtractor() {
    }

    /** Extract, or null if the chunk carries nothing worth storing. */
    public static CsLodChunk extract(final LevelChunk chunk) {
        final LevelChunkSection[] sections = chunk.getSections();
        if (sections.length == 0) {
            return null;
        }

        //[[[cog
        // import cog, compat
        // dim = compat.dimension_identifier_call(mcver)
        // cog.outl("final String dimension = chunk.getLevel().dimension().%s().toString();" % dim)
        // cog.outl("final int chunkX = chunk.getPos().%s;" % compat.chunkpos_x(mcver))
        // cog.outl("final int chunkZ = chunk.getPos().%s;" % compat.chunkpos_z(mcver))
        // cog.outl("final int minSectionY = chunk.%s();" % compat.chunk_min_section_call(mcver))
        //]]]
        //[[[end]]]
        final LevelLightEngine light = chunk.getLevel().getLightEngine();

        final Palette blocks = new Palette();
        final Palette biomes = new Palette();
        final List<CsLodChunk.Section> out = new ArrayList<>(sections.length);

        for (int i = 0; i < sections.length; i++) {
            final LevelChunkSection section = sections[i];
            final SectionPos pos = SectionPos.of(chunkX, minSectionY + i, chunkZ);
            out.add(extractSection(section, pos, light, blocks, biomes));
        }

        return new CsLodChunk(dimension, chunkX, chunkZ, minSectionY,
                blocks.entries(), biomes.entries(), out);
    }

    private static CsLodChunk.Section extractSection(final LevelChunkSection section,
                                                     final SectionPos pos,
                                                     final LevelLightEngine light,
                                                     final Palette blocks,
                                                     final Palette biomes) {
        // ---- blocks (per voxel, YZX order) ----
        int uniformBlock = -1;
        int[] blockIndices = null;
        if (section.hasOnlyAir()) {
            uniformBlock = blocks.id(BlockStateParser.serialize(section.getBlockState(0, 0, 0)));
        } else {
            blockIndices = new int[CsLodChunk.BLOCKS_PER_SECTION];
            int first = -1;
            boolean uniform = true;
            int n = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        final int id = blocks.id(BlockStateParser.serialize(state));
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

        // ---- biomes (4x4x4 -- exactly how Minecraft stores them, so this is lossless) ----
        int uniformBiome = -1;
        int[] biomeIndices = new int[CsLodChunk.BIOMES_PER_SECTION];
        int firstBiome = -1;
        boolean uniformB = true;
        int b = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    final Holder<Biome> holder = section.getNoiseBiome(x, y, z);
                    //[[[cog
                    // import cog, compat
                    // cog.outl("final int id = biomes.id(holder.unwrapKey()")
                    // cog.outl("        .map(key -> key.%s().toString())" % compat.dimension_identifier_call(mcver))
                    // cog.outl("        .orElse(\"minecraft:plains\"));")
                    //]]]
                    //[[[end]]]
                    biomeIndices[b++] = id;
                    if (firstBiome < 0) {
                        firstBiome = id;
                    } else if (id != firstBiome) {
                        uniformB = false;
                    }
                }
            }
        }
        if (uniformB) {
            uniformBiome = firstBiome;
            biomeIndices = null;
        }

        // ---- light: sky and block, SEPARATE, present even for pure-air sections ----
        final Light sky = extractLight(light, LightLayer.SKY, pos);
        final Light block = extractLight(light, LightLayer.BLOCK, pos);

        return new CsLodChunk.Section(blockIndices, uniformBlock, biomeIndices, uniformBiome,
                sky.packed, sky.uniform, block.packed, block.uniform);
    }

    private static Light extractLight(final LevelLightEngine engine, final LightLayer layer, final SectionPos pos) {
        final DataLayer data = engine.getLayerListener(layer).getDataLayerData(pos);
        if (data == null || data.isEmpty()) {
            // No stored data for this section: its light is uniform by construction. Sample one
            // position through the layer listener so that (for example) open sky above the terrain
            // records 15 rather than a black 0 -- DH would render an unlit LOD otherwise.
            final int value = engine.getLayerListener(layer)
                    .getLightValue(pos.origin().offset(0, 0, 0));
            return new Light(null, Math.max(0, Math.min(15, value)));
        }

        final byte[] packed = new byte[CsLodChunk.LIGHT_BYTES];
        int first = -1;
        boolean uniform = true;
        int n = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int value = Math.max(0, Math.min(15, data.get(x, y, z)));
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
            final int id = order.size();
            ids.put(value, id);
            order.add(value);
            return id;
        }

        private List<String> entries() {
            return order;
        }
    }

    /** Convenience for callers that only have a BlockPos-shaped origin. */
    static BlockPos origin(final SectionPos pos) {
        return pos.origin();
    }
}
