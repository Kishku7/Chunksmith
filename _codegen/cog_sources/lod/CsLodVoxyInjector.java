package com.kishku7.chunksmith.lod;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Replays a CSLOD store into voxy. Drives {@code /cslod inject}.
 *
 * <p>This is the payoff of the neutral format: a world pregenerated with ChunkSmith -- possibly long
 * before voxy was ever installed -- can be turned into voxy LODs after the fact, with no world
 * regeneration and no re-reading of region files.
 *
 * <p>Injection goes through {@link VoxelIngestService#rawIngest}, NOT {@code tryAutoIngestChunk}:
 * rawIngest takes the section and its light directly, so we hand voxy the REAL light that was captured at
 * generation time. (rawIngest has no light gate at all, which is precisely why the light we stored has to
 * be right -- a mistake here yields silently black LODs rather than an error.)
 *
 * <p>Throttled on voxy's own queue: its ingest deque is UNBOUNDED and never reports saturation, so a
 * backfill that just hammered it would OOM. We watch {@code getTaskCount()} and wait.
 *
 * <p>Generated ONLY where a voxy jar exists to compile against: Fabric 1.21.11 and Fabric 26.x. See
 * {@link VoxyLodSink}.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
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
     * Replay the whole store for one dimension into voxy. Runs on the CALLING thread -- callers should
     * hand it a background thread, not the server thread.
     *
     * @param progress receives human-readable progress lines
     * @return number of chunks injected
     */
    public static int inject(final ServerLevel level, final Path storeRoot, final Consumer<String> progress)
            throws IOException {
        final WorldIdentifier world = WorldIdentifier.of(level);

        final int[] chunks = {0};
        final int[] sections = {0};

        final int visited = CsLodRegionStore.forEachChunk(storeRoot, record -> {
            awaitVoxyCapacity();
            sections[0] += injectChunk(level, world, record);
            chunks[0]++;
            if (chunks[0] % 500 == 0) {
                progress.accept("injected " + chunks[0] + " chunks (" + sections[0] + " sections)");
            }
        });

        progress.accept("done: " + chunks[0] + " chunks, " + sections[0] + " sections injected into voxy"
                + (visited == chunks[0] ? "" : " (" + visited + " visited)"));
        return chunks[0];
    }

    private static int injectChunk(final ServerLevel level,
                                   final WorldIdentifier world,
                                   final CsLodChunk record) {
        int injected = 0;
        final List<CsLodChunk.Section> sections = record.getSections();
        for (int i = 0; i < sections.size(); i++) {
            final CsLodChunk.Section section = sections.get(i);
            // The reconstruction (and every MC-version drift in it) lives in ONE place.
            final LevelChunkSection rebuilt = CsLodSectionBuilder.rebuild(level, record, section);
            final DataLayer sky = light(section.getSkyLight(), section.getUniformSky());
            final DataLayer block = light(section.getBlockLight(), section.getUniformBlockLight());

            VoxelIngestService.rawIngest(world, rebuilt,
                    record.getChunkX(), record.getMinSectionY() + i, record.getChunkZ(),
                    block, sky);
            injected++;
        }
        return injected;
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
}
