package com.kishku7.chunksmith.lod;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Feeds freshly generated chunks straight into voxy's ingest service.
 *
 * <p>This class hard-references voxy types, so it MUST NOT be loaded unless voxy is present --
 * {@link LodSupport} is responsible for that gate. Compiled against voxy 0.2.16-beta (the current
 * Modrinth release for MC 26.1.2), which ships mojmap-named, so no remapping is involved.
 *
 * <p>Scope (P0): singleplayer / integrated server only. Voxy's instance factory is installed by
 * VoxyClient, so on a dedicated server {@code VoxyCommon.getInstance()} is null and there is no
 * engine to ingest into. That case is handled by the streaming path, not here.
 */
public final class VoxyLodSink implements LodSink {

    /**
     * Voxy's ingest gate requires the chunk's light to be real (sections must report
     * LIGHT_AND_DATA). ChunkSmith generates at ChunkStatus.FULL, which is downstream of the LIGHT
     * status, so the server light engine has already run and this holds.
     */
    @Override
    public boolean offer(final Object chunk) {
        if (!(chunk instanceof final LevelChunk levelChunk)) {
            return true;
        }
        if (VoxyCommon.getInstance() == null) {
            // No engine (dedicated server, or voxy not initialized for this world). Nothing to do,
            // and refusing here would stall pregen forever.
            return true;
        }
        return VoxelIngestService.tryAutoIngestChunk(levelChunk);
    }

    @Override
    public int queueDepth() {
        final var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return 0;
        }
        return instance.getIngestService().getTaskCount();
    }

    @Override
    public String toString() {
        return "VoxyLodSink";
    }
}
