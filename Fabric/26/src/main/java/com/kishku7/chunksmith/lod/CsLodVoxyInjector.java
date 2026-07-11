package com.kishku7.chunksmith.lod;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Replays a CSLOD store into voxy.
 *
 * <p>This is the payoff of the neutral format: a world pregenerated with ChunkSmith -- possibly long
 * before voxy was ever installed -- can be turned into voxy LODs after the fact, with no world
 * regeneration and no re-reading of region files.
 *
 * <p>Injection goes through {@link VoxelIngestService#rawIngest}, NOT {@code tryAutoIngestChunk}:
 * rawIngest takes the section and its light directly, so we hand voxy the REAL light that was captured
 * at generation time. (rawIngest has no light gate at all, which is precisely why the light we stored
 * has to be right -- a mistake here yields silently black LODs rather than an error.)
 *
 * <p>Throttled on voxy's own queue: its ingest deque is UNBOUNDED and never reports saturation, so
 * a backfill that just hammered it would OOM. We watch {@code getTaskCount()} and wait.
 */
public final class CsLodVoxyInjector {

    /** Pause the backfill while voxy's ingest backlog is above this. */
    private static final int VOXY_QUEUE_LIMIT = 512;

    private CsLodVoxyInjector() {
    }

    /** True when there is a voxy engine to inject into (i.e. singleplayer / a client instance). */
    public static boolean voxyAvailable() {
        try {
            return VoxyCommon.getInstance() != null;
        } catch (final LinkageError error) {
            return false;
        }
    }

    /**
     * Replay the whole store for one dimension into voxy. Runs on the CALLING thread -- callers
     * should hand it a background thread, not the server thread.
     *
     * @param progress receives human-readable progress lines
     * @return number of chunks injected
     */
    public static int inject(final ServerLevel level, final Path storeRoot, final Consumer<String> progress)
            throws IOException {
        final WorldIdentifier world = WorldIdentifier.of(level);
        final PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
        final BlockStateCache blockCache = new BlockStateCache(level);
        final BiomeCache biomeCache = new BiomeCache(level);

        final int[] chunks = {0};
        final int[] sections = {0};

        final int visited = CsLodRegionStore.forEachChunk(storeRoot, record -> {
            awaitVoxyCapacity();
            sections[0] += injectChunk(world, factory, blockCache, biomeCache, record);
            chunks[0]++;
            if (chunks[0] % 500 == 0) {
                progress.accept("injected " + chunks[0] + " chunks (" + sections[0] + " sections)");
            }
        });

        progress.accept("done: " + chunks[0] + " chunks, " + sections[0] + " sections injected into voxy"
                + (visited == chunks[0] ? "" : " (" + visited + " visited)"));
        return chunks[0];
    }

    private static int injectChunk(final WorldIdentifier world,
                                   final PalettedContainerFactory factory,
                                   final BlockStateCache blockCache,
                                   final BiomeCache biomeCache,
                                   final CsLodChunk record) {
        final List<String> blockPalette = record.getBlockPalette();
        final List<String> biomePalette = record.getBiomePalette();

        // Resolve the string palettes ONCE per chunk, not per voxel.
        final List<BlockState> blocks = new ArrayList<>(blockPalette.size());
        for (final String entry : blockPalette) {
            blocks.add(blockCache.get(entry));
        }
        final List<Holder<Biome>> biomes = new ArrayList<>(biomePalette.size());
        for (final String entry : biomePalette) {
            biomes.add(biomeCache.get(entry));
        }

        int injected = 0;
        final List<CsLodChunk.Section> sections = record.getSections();
        for (int i = 0; i < sections.size(); i++) {
            final CsLodChunk.Section section = sections.get(i);
            final LevelChunkSection rebuilt = rebuild(factory, blocks, biomes, section);
            final DataLayer sky = light(section.getSkyLight(), section.getUniformSky());
            final DataLayer block = light(section.getBlockLight(), section.getUniformBlockLight());

            VoxelIngestService.rawIngest(world, rebuilt,
                    record.getChunkX(), record.getMinSectionY() + i, record.getChunkZ(),
                    block, sky);
            injected++;
        }
        return injected;
    }

    private static LevelChunkSection rebuild(final PalettedContainerFactory factory,
                                             final List<BlockState> blocks,
                                             final List<Holder<Biome>> biomes,
                                             final CsLodChunk.Section section) {
        final PalettedContainer<BlockState> states = factory.createForBlockStates();
        final PalettedContainer<Holder<Biome>> biomeContainer = factory.createForBiomes();

        if (section.getUniformBlock() >= 0) {
            final BlockState state = blocks.get(section.getUniformBlock());
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
                        states.set(x, y, z, blocks.get(indices[n++]));
                    }
                }
            }
        }

        if (section.getUniformBiome() >= 0) {
            final Holder<Biome> biome = biomes.get(section.getUniformBiome());
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        biomeContainer.set(x, y, z, biome);
                    }
                }
            }
        } else {
            final int[] indices = section.getBiomes();
            int n = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        biomeContainer.set(x, y, z, biomes.get(indices[n++]));
                    }
                }
            }
        }

        // The (states, biomes) constructor recalculates the section's block counts for us.
        return new LevelChunkSection(states, biomeContainer);
    }

    /** Rebuild a DataLayer from our packed nibbles, or from a single uniform value. */
    private static DataLayer light(final byte[] packed, final int uniform) {
        if (packed != null) {
            return new DataLayer(packed.clone());
        }
        return uniform > 0 ? new DataLayer(uniform) : new DataLayer();
    }

    private static void awaitVoxyCapacity() {
        try {
            while (VoxyCommon.getInstance() != null
                    && VoxyCommon.getInstance().getIngestService().getTaskCount() > VOXY_QUEUE_LIMIT) {
                Thread.sleep(20L);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Block state strings -> BlockState, resolved once per distinct string. */
    private static final class BlockStateCache {
        private final ServerLevel level;
        private final java.util.Map<String, BlockState> cache = new java.util.HashMap<>();

        private BlockStateCache(final ServerLevel level) {
            this.level = level;
        }

        private BlockState get(final String value) {
            return cache.computeIfAbsent(value, key -> {
                try {
                    return BlockStateParser.parseForBlock(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK), key, false).blockState();
                } catch (final CommandSyntaxException e) {
                    // An unknown/removed block (a mod was uninstalled since the pregen). Air is the
                    // honest answer: better a hole than a crash, and it is visible in-world.
                    return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                }
            });
        }
    }

    /** Biome ids -> Holder<Biome>, resolved once per distinct id. */
    private static final class BiomeCache {
        private final ServerLevel level;
        private final java.util.Map<String, Holder<Biome>> cache = new java.util.HashMap<>();

        private BiomeCache(final ServerLevel level) {
            this.level = level;
        }

        private Holder<Biome> get(final String value) {
            return cache.computeIfAbsent(value, key -> {
                final Identifier id = Identifier.parse(key);
                return level.registryAccess().lookupOrThrow(Registries.BIOME)
                        .get(ResourceKey.create(Registries.BIOME, id))
                        .orElseGet(() -> level.registryAccess().lookupOrThrow(Registries.BIOME)
                                .get(ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains")))
                                .orElseThrow());
            });
        }
    }
}
