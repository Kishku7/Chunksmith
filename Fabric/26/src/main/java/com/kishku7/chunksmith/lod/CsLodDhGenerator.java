package com.kishku7.chunksmith.lod;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.AbstractDhApiChunkWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.DhApi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves Distant Horizons straight out of the CSLOD store.
 *
 * <p>DH does not accept pushed data -- it PULLS, through a world-generator override. So instead of
 * generating anything, we register as its generator and answer from what ChunkSmith already
 * pregenerated. For a pregenerated area DH's LODs therefore appear essentially instantly, with no
 * worldgen cost at all.
 *
 * <p><b>The format was designed for exactly this.</b> DH builds its ids from vanilla registry strings
 * ({@code wrapperFactory.getDefaultBlockStateWrapper("minecraft:oak_stairs[...]", level)}), and CSLOD
 * already stores full block STATE strings -- so nothing is translated, re-mapped or looked up through
 * a foreign id table. Sky and block light are stored SEPARATELY for the same reason: DH will not take
 * voxy's blended byte.
 *
 * <p><b>Light offset.</b> DH samples a column's light from the block ABOVE the surface (y+1), so the
 * data point for a solid block carries the light of the air above it. We apply that offset here.
 *
 * <p><b>The trade-off, stated plainly.</b> Registering a world-generator override REPLACES DH's own
 * distant generator for this level. Chunks ChunkSmith has not pregenerated come back EMPTY -- DH gets no
 * data for them rather than generating them itself. That is why this is opt-in
 * ({@code lodDhOverride}, default false): it is the right behaviour for a world you have pregenerated
 * and the wrong one for a world you have not.
 */
public final class CsLodDhGenerator extends AbstractDhApiChunkWorldGenerator {

    private final IDhApiLevelWrapper level;
    private final CsLodRegionStore store;
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong missed = new AtomicLong();

    /** Wrapper caches: resolve each distinct palette string once, not once per data point. */
    private final Map<String, IDhApiBlockStateWrapper> blockWrappers = new HashMap<>();
    private final Map<String, IDhApiBiomeWrapper> biomeWrappers = new HashMap<>();

    public CsLodDhGenerator(final IDhApiLevelWrapper level, final Path storeRoot) {
        this.level = level;
        this.store = new CsLodRegionStore(storeRoot);
    }

    /** We hand DH ready-made API chunks, not vanilla ones. */
    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_CHUNKS;
    }

    @Override
    public void preGeneratorTaskStart() {
        // Nothing to warm up: we read files, we do not generate.
    }

    @Override
    public Object[] generateChunk(final int chunkX, final int chunkZ, final EDhApiDistantGeneratorMode mode) {
        // Never called: getReturnType() is API_CHUNKS.
        throw new UnsupportedOperationException("ChunkSmith serves API_CHUNKS, not vanilla chunks");
    }

    @Override
    public DhApiChunk generateApiChunk(final int chunkX, final int chunkZ, final EDhApiDistantGeneratorMode mode) {
        final CsLodChunk record;
        try {
            record = store.read(chunkX, chunkZ);
        } catch (final IOException e) {
            missed.incrementAndGet();
            return emptyChunk(chunkX, chunkZ);
        }
        if (record == null) {
            // Not pregenerated. DH gets no data for this chunk (see the class note).
            missed.incrementAndGet();
            return emptyChunk(chunkX, chunkZ);
        }

        final int bottomY = record.getMinSectionY() * 16;
        final int topY = bottomY + record.getSections().size() * 16;
        final DhApiChunk chunk = DhApiChunk.create(chunkX, chunkZ, bottomY, topY);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setDataPoints(x, z, column(record, x, z, bottomY));
            }
        }
        served.incrementAndGet();
        return chunk;
    }

    /**
     * Build one 1x1 column of data points, bottom-up, gap-free -- DH requires columns that neither
     * overlap nor leave holes.
     */
    private List<DhApiTerrainDataPoint> column(final CsLodChunk record, final int x, final int z, final int bottomY) {
        final List<CsLodChunk.Section> sections = record.getSections();
        final int height = sections.size() * 16;

        // Flatten the column first so we can look one block UP for the light DH wants.
        final String[] states = new String[height];
        final String[] biomes = new String[height];
        final int[] skyLight = new int[height];
        final int[] blockLight = new int[height];

        for (int s = 0; s < sections.size(); s++) {
            final CsLodChunk.Section section = sections.get(s);
            for (int y = 0; y < 16; y++) {
                final int index = s * 16 + y;
                final int voxel = y * 256 + z * 16 + x;

                final int blockId = section.getUniformBlock() >= 0
                        ? section.getUniformBlock()
                        : section.getBlocks()[voxel];
                states[index] = record.getBlockPalette().get(blockId);

                final int biomeCell = (y >> 2) * 16 + (z >> 2) * 4 + (x >> 2);
                final int biomeId = section.getUniformBiome() >= 0
                        ? section.getUniformBiome()
                        : section.getBiomes()[biomeCell];
                biomes[index] = record.getBiomePalette().get(biomeId);

                skyLight[index] = section.getUniformSky() >= 0
                        ? section.getUniformSky()
                        : CsLodChunk.Section.nibble(section.getSkyLight(), voxel);
                blockLight[index] = section.getUniformBlockLight() >= 0
                        ? section.getUniformBlockLight()
                        : CsLodChunk.Section.nibble(section.getBlockLight(), voxel);
            }
        }

        // Run-length merge: DH stores columns as runs, and one data point per block would be both
        // enormous and pointless.
        final List<DhApiTerrainDataPoint> points = new ArrayList<>();
        int runStart = 0;
        for (int y = 1; y <= height; y++) {
            final boolean end = y == height;
            final boolean same = !end
                    && states[y].equals(states[runStart])
                    && biomes[y].equals(biomes[runStart])
                    && skyOf(skyLight, y, height) == skyOf(skyLight, runStart, height)
                    && blockOf(blockLight, y, height) == blockOf(blockLight, runStart, height);
            if (same) {
                continue;
            }
            points.add(DhApiTerrainDataPoint.create(
                    (byte) 0,
                    blockOf(blockLight, runStart, height),
                    skyOf(skyLight, runStart, height),
                    bottomY + runStart,
                    bottomY + y,
                    blockWrapper(states[runStart]),
                    biomeWrapper(biomes[runStart])));
            runStart = y;
        }
        return points;
    }

    /**
     * The "no data" answer for a chunk ChunkSmith never pregenerated.
     *
     * <p>It must NOT be null. {@code AbstractDhApiChunkWorldGenerator.generateApiChunks} feeds our
     * return value straight to DH's result consumer with no null check, and
     * {@code LodDataBuilder.createFromApiChunkData} dereferences it immediately -- a null throws an
     * NPE inside DH's {@code thenRun}, which never completes the task's future. The task then sits in
     * DH's in-progress map forever, {@code isGeneratorBusy()} latches true, and the level's whole
     * world-gen queue dies SILENTLY (no log line at all). DH's own API says so explicitly:
     * "If you want to remove all data from a column please clear the list or pass in an empty list."
     * So the honest "nothing here" answer is a chunk of empty columns.
     */
    private DhApiChunk emptyChunk(final int chunkX, final int chunkZ) {
        final DhApiChunk chunk = DhApiChunk.create(
                chunkX, chunkZ, level.getMinHeight(), level.getMaxHeight());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setDataPoints(x, z, List.of());
            }
        }
        return chunk;
    }

    /** DH takes a column's light from the block ABOVE it; at the ceiling there is nothing above. */
    private static int skyOf(final int[] skyLight, final int y, final int height) {
        return y + 1 < height ? skyLight[y + 1] : skyLight[height - 1];
    }

    private static int blockOf(final int[] blockLight, final int y, final int height) {
        return y + 1 < height ? blockLight[y + 1] : blockLight[height - 1];
    }

    private IDhApiBlockStateWrapper blockWrapper(final String state) {
        return blockWrappers.computeIfAbsent(state, key -> {
            try {
                return DhApi.Delayed.wrapperFactory.getDefaultBlockStateWrapper(key, level);
            } catch (final IOException e) {
                // Unknown block (a mod was removed since the pregen): air is the honest answer.
                return DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
            }
        });
    }

    private IDhApiBiomeWrapper biomeWrapper(final String biome) {
        return biomeWrappers.computeIfAbsent(biome, key -> {
            try {
                return DhApi.Delayed.wrapperFactory.getBiomeWrapper(key, level);
            } catch (final IOException e) {
                throw new IllegalStateException("ChunkSmith: DH rejected biome id " + key, e);
            }
        });
    }

    /** Chunks served from the store / asked for but never pregenerated. */
    public long getServedCount() {
        return served.get();
    }

    public long getMissedCount() {
        return missed.get();
    }

    @Override
    public void close() {
        try {
            store.close();
        } catch (final IOException e) {
            System.out.println("[chunksmith] failed to close the LOD store for DH: " + e);
        }
    }
}
