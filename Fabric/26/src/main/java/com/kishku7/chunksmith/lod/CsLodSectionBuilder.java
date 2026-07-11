package com.kishku7.chunksmith.lod;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds a vanilla {@link LevelChunkSection} from a stored CSLOD section.
 *
 * <p>Shared by every consumer that has to turn stored data back into Minecraft objects: the voxy
 * injector, the DH pusher, and (later) Chunksmith-Client. One reconstruction, one place -- this is the
 * inverse of {@code CsLodExtractor} and the two must stay in step.
 *
 * <p>Palette strings are resolved through caches: a chunk's palette has tens of entries, but a store has
 * millions of voxels, so resolving per-voxel would dominate the cost.
 */
public final class CsLodSectionBuilder {

    private static final Map<String, BlockState> BLOCK_CACHE = new HashMap<>();
    private static final Map<String, Holder<Biome>> BIOME_CACHE = new HashMap<>();

    private CsLodSectionBuilder() {
    }

    /** Rebuild one section. The (states, biomes) constructor recalculates the section's block counts. */
    public static LevelChunkSection rebuild(final ServerLevel level,
                                            final CsLodChunk record,
                                            final CsLodChunk.Section section) {
        final PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
        final PalettedContainer<BlockState> states = factory.createForBlockStates();
        final PalettedContainer<Holder<Biome>> biomes = factory.createForBiomes();

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
                return BlockStateParser.parseForBlock(
                        level.registryAccess().lookupOrThrow(Registries.BLOCK), key, false).blockState();
            } catch (final CommandSyntaxException e) {
                return Blocks.AIR.defaultBlockState();
            }
        });
    }

    /** A biome id -> Holder. Unknown biome -> plains. */
    public static Holder<Biome> biome(final ServerLevel level, final String value) {
        return BIOME_CACHE.computeIfAbsent(value, key -> {
            final var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            return registry.get(ResourceKey.create(Registries.BIOME, Identifier.parse(key)))
                    .orElseGet(() -> registry
                            .get(ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains")))
                            .orElseThrow());
        });
    }
}
