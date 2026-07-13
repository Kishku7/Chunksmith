package com.kishku7.chunksmith.lod;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Feeds freshly generated chunks straight into voxy's ingest service.
 *
 * <p>This class hard-references voxy types, so it MUST NOT be loaded unless voxy is present --
 * {@link LodSupport} is responsible for that gate. Compiled against voxy 0.2.16-beta.
 *
 * <p>Generated ONLY where a voxy jar exists to compile against: Fabric 1.21.11 and Fabric 26.x. voxy is
 * Fabric-only and upstream has never published a 1.20.1 or a 1.21.1 build, so on every other cell this
 * class does not exist at all -- a compile-time-absent seam. The mod never claims a renderer it cannot
 * feed.
 *
 * <p>Scope: singleplayer / integrated server only. Voxy's instance factory is installed by VoxyClient, so
 * on a dedicated server {@code VoxyCommon.getInstance()} is null and there is no engine to ingest into.
 * That case is handled by the streaming path (the LOD client half), not here.
 *
 * <p><b>A voxy that will not take our chunks must not take the pregen down with it.</b> voxy is forked
 * constantly, and a fork that changed {@code tryAutoIngestChunk} would throw a {@code NoSuchMethodError}
 * -- an Error, straight through every {@code catch (Exception)} in the pregen pipeline -- on the FIRST
 * chunk. So the sink absorbs a {@link LinkageError} once, says out loud what happened, and then stands
 * down for the session: the pregen keeps running and still writes the CSLOD store (which is the durable
 * artifact), the player just does not get live voxy ingest. Degrade, and be LOUD about it.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
 */
public final class VoxyLodSink implements LodSink {

    /** Warn key: voxy is here, but it is not the voxy we were built against. */
    private static final String CAUSE_INCOMPATIBLE = "voxy-incompatible";

    /**
     * Set once voxy has proved it cannot accept our chunks. A LinkageError is structural -- the member is
     * not in the jar that is loaded -- so it cannot heal, and retrying it per chunk would spam the log for
     * a result that cannot change.
     */
    private volatile boolean broken;

    /**
     * Voxy's ingest gate requires the chunk's light to be real (sections must report
     * LIGHT_AND_DATA). ChunkSmith generates at ChunkStatus.FULL, which is downstream of the LIGHT
     * status, so the server light engine has already run and this holds.
     */
    @Override
    public boolean offer(final Object chunk) {
        if (broken) {
            // Accept and drop: the pregen must not stall on a renderer we have already ruled out.
            return true;
        }
        if (!(chunk instanceof final LevelChunk levelChunk)) {
            return true;
        }
        try {
            if (VoxyCommon.getInstance() == null) {
                // No engine (dedicated server, or voxy not initialized for this world). Nothing to do,
                // and refusing here would stall pregen forever.
                return true;
            }
            return VoxelIngestService.tryAutoIngestChunk(levelChunk);
        } catch (final LinkageError error) {
            standDown(error);
            return true;
        }
    }

    @Override
    public int queueDepth() {
        if (broken) {
            return 0;
        }
        try {
            final var instance = VoxyCommon.getInstance();
            if (instance == null) {
                return 0;
            }
            return instance.getIngestService().getTaskCount();
        } catch (final LinkageError error) {
            standDown(error);
            return 0;
        }
    }

    /** Rule voxy out for this session, and SAY SO -- once, in words the player can act on. */
    private void standDown(final LinkageError error) {
        broken = true;
        LodWarnings.once(CAUSE_INCOMPATIBLE,
                "voxy is installed, but this build of it does not match the voxy Chunksmith was built"
                        + " against (" + error + "). Chunksmith will keep generating and will keep writing"
                        + " its own LOD store, but it can no longer feed voxy directly -- distant terrain"
                        + " will not appear in voxy while you generate. This normally means a voxy fork that"
                        + " changed a method or a field. Please report it, with your voxy version.");
    }

    @Override
    public String toString() {
        return "VoxyLodSink" + (broken ? " (stood down -- incompatible voxy)" : "");
    }
}
