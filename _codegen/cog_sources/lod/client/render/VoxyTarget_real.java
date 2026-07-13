package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.CsLodChunk;
import com.kishku7.chunksmith.lod.CsLodSectionBuilder;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Feeds downloaded CSLOD records into the player's voxy.
 *
 * <p>Hard-references voxy, so it is only ever class-loaded once {@code isModLoaded("voxy")} has passed.
 *
 * <p>Uses {@code rawIngest}, not {@code tryAutoIngestChunk}: rawIngest takes the section and its light
 * DIRECTLY, so voxy gets the REAL light that was captured on the server at generation time -- which is the
 * whole point of storing sky and block light separately in CSLOD.
 *
 * <p><b>rawIngest has NO light gate.</b> Hand it wrong light and it will cheerfully produce BLACK LODs and
 * report success. So the light we ship has to be right, and the only way to know is to look at it.
 *
 * <p>Throttled on voxy's own queue: its ingest deque is UNBOUNDED and never reports saturation, so an
 * unthrottled replay of a large store would drive the heap into an OOM. (That is the failure that OOMed
 * Voxy WorldGen V2 badly enough that upstream voxy ships a hard `breaks` against it.)
 */
public final class VoxyTarget {

    /** Pause while voxy's ingest backlog is above this. */
    private static final int QUEUE_LIMIT = 512;

    private VoxyTarget() {
    }

    /**
     * Whether THIS loader has a voxy adapter at all. True here; false in the NeoForge copy.
     *
     * <p>{@link com.kishku7.chunksmith.lod.client.Renderers#hasVoxy()} is gated on this, so a NeoForge client
     * that somehow has a mod called {@code voxy} does not get announced to the server as a voxy client that
     * we then cannot feed. See the NeoForge seam copy of this class for why it does not exist there.
     */
    public static boolean supported() {
        return true;
    }

    /** True when there is a voxy engine to ingest into. */
    public static boolean available() {
        try {
            return VoxyCommon.getInstance() != null;
        } catch (final LinkageError error) {
            return false;
        }
    }

    /**
     * Inject one chunk record.
     *
     * @return number of sections ingested
     */
    public static int inject(final Level level, final CsLodChunk record) {
        final WorldIdentifier world = WorldIdentifier.of(level);
        int ingested = 0;
        for (int i = 0; i < record.getSections().size(); i++) {
            awaitCapacity();
            final CsLodChunk.Section section = record.getSections().get(i);
            final LevelChunkSection rebuilt = CsLodSectionBuilder.rebuild(level, record, section);
            final DataLayer sky = light(section.getSkyLight(), section.getUniformSky());
            final DataLayer block = light(section.getBlockLight(), section.getUniformBlockLight());

            VoxelIngestService.rawIngest(world, rebuilt,
                    record.getChunkX(), record.getMinSectionY() + i, record.getChunkZ(),
                    block, sky);
            ingested++;
        }
        return ingested;
    }

    /** Rebuild a DataLayer from our packed nibbles, or from a single uniform value. */
    private static DataLayer light(final byte[] packed, final int uniform) {
        if (packed != null) {
            return new DataLayer(packed.clone());
        }
        return uniform > 0 ? new DataLayer(uniform) : new DataLayer();
    }

    private static void awaitCapacity() {
        try {
            while (VoxyCommon.getInstance() != null
                    && VoxyCommon.getInstance().getIngestService().getTaskCount() > QUEUE_LIMIT) {
                Thread.sleep(20L);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
