package com.kishku7.chunksmith.lod;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
//[[[cog
// import cog, compat
// for line in compat.section_builder_imports(mcver):
//     cog.outl(line)
//]]]
//[[[end]]]

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rebuilds a vanilla {@link LevelChunkSection} from a stored CSLOD section -- the inverse of
 * {@code CsLodExtractor}, and the two must stay in step.
 *
 * <p>Shared by every consumer that has to turn stored data back into Minecraft objects: the voxy
 * injector and the Distant Horizons pusher. One reconstruction, one place.
 *
 * <p>Palette strings are resolved through caches: a chunk's palette has tens of entries, but a store has
 * millions of voxels, so resolving per-voxel would dominate the cost.
 *
 * <p><b>This is the file MC churn hits hardest,</b> and every drifting symbol in it is emitted by
 * {@code compat.py} (see its CsLodSectionBuilder section for the exact 1.21.11 boundary):
 * <ul>
 *   <li>the paletted containers. Below 1.21.11 you build them by hand
 *       ({@code new PalettedContainer<>(IdMap, default, Strategy.SECTION_STATES)}); from 1.21.11 the
 *       {@code Strategy} constants are GONE and {@code PalettedContainerFactory} makes them for you. The
 *       two-arg {@code LevelChunkSection(states, biomes)} constructor is the ONE thing stable across the
 *       whole 1.20.1 -&gt; 26 range, so everything funnels into it;</li>
 *   <li>{@code RegistryAccess.registryOrThrow} -&gt; {@code lookupOrThrow};</li>
 *   <li>{@code Registry.getHolder}/{@code getHolderOrThrow} -&gt; {@code get}/{@code getOrThrow} -- and
 *       {@code getOrThrow} exists on BOTH sides with a different return type, so it is emitted whole,
 *       never renamed;</li>
 *   <li>{@code ResourceLocation} -&gt; {@code Identifier}, and the fact that the {@code (String)}
 *       constructor is the only parse on 1.20.1.</li>
 * </ul>
 * A {@code Registry} is only itself a {@code HolderLookup} from 1.21.11; before that
 * {@code BlockStateParser.parseForBlock} needs {@code .asLookup()}.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodSectionBuilder {

    // Static + shared by both callers (the voxy injector and the DH pusher), which each run on their own
    // background thread -- and a user can start /cslod inject and /cslod dhpush at once. ConcurrentHashMap,
    // not HashMap, or two concurrent computeIfAbsent resolves can corrupt the map.
    private static final Map<String, BlockState> BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Holder<Biome>> BIOME_CACHE = new ConcurrentHashMap<>();

    private CsLodSectionBuilder() {
    }

    /** Rebuild one section. The (states, biomes) constructor recalculates the section's block counts. */
    //[[[cog
    // import cog, compat
    // suppress = compat.deprecation_suppression(mcver, loader)
    // if suppress:
    //     cog.outl(suppress)
    //]]]
    //[[[end]]]
    public static LevelChunkSection rebuild(final ServerLevel level,
                                            final CsLodChunk record,
                                            final CsLodChunk.Section section) {
        //[[[cog
        // import cog, compat
        // for line in compat.palette_containers(mcver):
        //     cog.outl(line)
        //]]]
        //[[[end]]]

        if (section.getUniformBlock() >= 0) {
            final BlockState state = blockState(level, record.getBlockPalette().get(section.getUniformBlock()));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        states.set(x, y, z, state);
                    }
                }
            }
        } else {
            final int[] indices = section.getBlocks();
            int n = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        states.set(x, y, z, blockState(level, record.getBlockPalette().get(indices[n++])));
                    }
                }
            }
        }

        if (section.getUniformBiome() >= 0) {
            final Holder<Biome> biome = biome(level, record.getBiomePalette().get(section.getUniformBiome()));
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        biomes.set(x, y, z, biome);
                    }
                }
            }
        } else {
            final int[] indices = section.getBiomes();
            int n = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        biomes.set(x, y, z, biome(level, record.getBiomePalette().get(indices[n++])));
                    }
                }
            }
        }

        return new LevelChunkSection(states, biomes);
    }

    /** A block STATE string -> BlockState. Unknown block (a mod was removed since pregen) -> air. */
    public static BlockState blockState(final ServerLevel level, final String value) {
        return BLOCK_CACHE.computeIfAbsent(value, key -> {
            try {
                //[[[cog
                // import cog, compat
                // cog.outl(compat.block_lookup_expr(mcver))
                //]]]
                //[[[end]]]
            } catch (final CommandSyntaxException e) {
                // An unknown/removed block (a mod was uninstalled since the pregen). Air is the honest
                // answer: better a hole than a crash, and it is visible in-world.
                return Blocks.AIR.defaultBlockState();
            }
        });
    }

    /** A biome id -> Holder. Unknown biome -> plains. */
    //[[[cog
    // import cog, compat
    // suppress = compat.removal_suppression(mcver, loader)
    // if suppress:
    //     cog.outl(suppress)
    //]]]
    //[[[end]]]
    public static Holder<Biome> biome(final ServerLevel level, final String value) {
        return BIOME_CACHE.computeIfAbsent(value, key -> {
            //[[[cog
            // import cog, compat
            // for line in compat.biome_lookup_body(mcver):
            //     cog.outl(line)
            //]]]
            //[[[end]]]
        });
    }
}
