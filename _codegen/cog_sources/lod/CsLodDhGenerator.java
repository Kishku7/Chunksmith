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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DH does not accept pushed data -- it pulls, through a world-generator override. So instead of
 * generating anything, ChunkSmith registers as its generator and answers from what it already
 * pregenerated. For a pregenerated area DH's LODs therefore appear essentially instantly, with no
 * worldgen cost at all.
 *
 * <p><b>The trade-off.</b> Registering a world-generator override replaces DH's own distant generator for
 * this level, so chunks ChunkSmith has not pregenerated come back empty. DH gets no data for them rather
 * than generating them itself. Hence opt-in: {@code lodDhOverride}, default false.
 *
 * <p>Nothing is translated on the way out. DH builds its ids from vanilla registry strings
 * ({@code wrapperFactory.getDefaultBlockStateWrapper("minecraft:oak_stairs[...]", level)}), and CSLOD
 * already stores full block STATE strings. No re-mapping, no foreign id table. Sky and block light are
 * stored separately for the same reason: DH will not take voxy's blended byte. Every symbol in this class
 * is {@code com.seibel.*} or ours and it names no Minecraft type at all, which is why one source compiles
 * unchanged on all eight LOD cells.
 *
 * <p>Light offset: DH samples a column's light from the block above the surface (y+1), so the data point
 * for a solid block carries the light of the air above it. We apply that offset here.
 */
public final class CsLodDhGenerator extends AbstractDhApiChunkWorldGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private final IDhApiLevelWrapper level;
    private final CsLodRegionStore store;
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong missed = new AtomicLong();

    /**
     * Wrapper caches: resolve each distinct palette string once, not once per data point. DH drives its
     * world-generator override from its own thread pool, so {@code generateApiChunk} (and therefore
     * {@code computeIfAbsent} on these maps) can run on several threads at once. ConcurrentHashMap, not
     * HashMap, or a concurrent resolve corrupts the map (a HashMap resize under two threads can spin
     * forever).
     */
    private final Map<String, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
    private final Map<String, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();

    public CsLodDhGenerator(IDhApiLevelWrapper level, Path storeRoot) {
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
    public Object[] generateChunk(int chunkX, int chunkZ, EDhApiDistantGeneratorMode mode) {
        // Never called: getReturnType() is API_CHUNKS.
        throw new UnsupportedOperationException("ChunkSmith serves API_CHUNKS, not vanilla chunks");
    }

    @Override
    public DhApiChunk generateApiChunk(int chunkX, int chunkZ, EDhApiDistantGeneratorMode mode) {
        CsLodChunk record;
        try {
            record = store.read(chunkX, chunkZ);
        } catch (IOException e) {
            missed.incrementAndGet();
            return emptyChunk(chunkX, chunkZ);
        }
        if (record == null) {
            // Not pregenerated. DH gets no data for this chunk (see the class note).
            missed.incrementAndGet();
            return emptyChunk(chunkX, chunkZ);
        }

        int bottomY = record.getMinSectionY() * 16;
        int topY = bottomY + record.getSections().size() * 16;
        DhApiChunk chunk = DhApiChunk.create(chunkX, chunkZ, bottomY, topY);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setDataPoints(x, z, column(record, x, z, bottomY));
            }
        }
        served.incrementAndGet();
        return chunk;
    }

    /**
     * Builds one 1x1 column of data points, bottom-up and gap-free. DH requires columns that neither overlap
     * nor leave holes.
     */
    private List<DhApiTerrainDataPoint> column(CsLodChunk record, int x, int z, int bottomY) {
        List<CsLodChunk.Section> sections = record.getSections();
        int height = sections.size() * 16;

        // Flatten the column first so we can look one block UP for the light DH wants.
        String[] states = new String[height];
        String[] biomes = new String[height];
        int[] skyLight = new int[height];
        int[] blockLight = new int[height];

        for (int s = 0; s < sections.size(); s++) {
            CsLodChunk.Section section = sections.get(s);
            for (int y = 0; y < 16; y++) {
                int index = s * 16 + y;
                int voxel = y * 256 + z * 16 + x;

                int blockId = section.getUniformBlock() >= 0
                        ? section.getUniformBlock()
                        : section.getBlocks()[voxel];
                states[index] = record.getBlockPalette().get(blockId);

                int biomeCell = (y >> 2) * 16 + (z >> 2) * 4 + (x >> 2);
                int biomeId = section.getUniformBiome() >= 0
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
        List<DhApiTerrainDataPoint> points = new ArrayList<>();
        int runStart = 0;
        for (int y = 1; y <= height; y++) {
            boolean end = y == height;
            boolean same = !end
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
     * Returns the "no data" answer for a chunk ChunkSmith never pregenerated, a chunk of empty columns.
     *
     * <p>It must not be null. {@code AbstractDhApiChunkWorldGenerator.generateApiChunks} feeds our
     * return value straight to DH's result consumer with no null check, and
     * {@code LodDataBuilder.createFromApiChunkData} dereferences it immediately; a null throws an
     * NPE inside DH's {@code thenRun}, which never completes the task's future. The task then sits in
     * DH's in-progress map forever, {@code isGeneratorBusy()} latches true, and the level's whole
     * world-gen queue dies silently (no log line at all). DH's own API says so explicitly:
     * "If you want to remove all data from a column please clear the list or pass in an empty list."
     */
    private DhApiChunk emptyChunk(int chunkX, int chunkZ) {
        DhApiChunk chunk = DhApiChunk.create(
                chunkX, chunkZ, level.getMinHeight(), level.getMaxHeight());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setDataPoints(x, z, List.of());
            }
        }
        return chunk;
    }

    /** DH takes a column's light from the block ABOVE it; at the ceiling there is nothing above. */
    private static int skyOf(int[] skyLight, int y, int height) {
        return y + 1 < height ? skyLight[y + 1] : skyLight[height - 1];
    }

    private static int blockOf(int[] blockLight, int y, int height) {
        return y + 1 < height ? blockLight[y + 1] : blockLight[height - 1];
    }

    private IDhApiBlockStateWrapper blockWrapper(String state) {
        return blockWrappers.computeIfAbsent(state, key -> {
            try {
                return DhApi.Delayed.wrapperFactory.getDefaultBlockStateWrapper(key, level);
            } catch (IOException e) {
                // Unknown block (a mod was removed since the pregen): air is the honest answer.
                return DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
            }
        });
    }

    private IDhApiBiomeWrapper biomeWrapper(String biome) {
        return biomeWrappers.computeIfAbsent(biome, key -> {
            try {
                return DhApi.Delayed.wrapperFactory.getBiomeWrapper(key, level);
            } catch (IOException e) {
                throw new IllegalStateException("ChunkSmith: DH rejected biome id " + key, e);
            }
        });
    }

    /**
     * Returns how many chunks were served from the store, against how many were asked for but never
     * pregenerated.
     */
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
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: failed to close the LOD store for DH: {}", e.toString());
        }
    }
}
