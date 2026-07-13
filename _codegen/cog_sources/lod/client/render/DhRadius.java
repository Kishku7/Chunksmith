package com.kishku7.chunksmith.lod.client.render;

import com.seibel.distanthorizons.api.DhApi;

/**
 * Reads Distant Horizons' ACTUAL configured render distance.
 *
 * <p>Hard-references DH, so only ever loaded once DH is known present.
 *
 * <p>This is what the server follows when deciding how much to send. Guessing would be wrong in both
 * directions: send more than the player can draw and it is bandwidth spent on terrain they will never see;
 * send less and they get visible holes at the edge of their view. The client is the only side that knows,
 * so the client says.
 */
public final class DhRadius {

    private DhRadius() {
    }

    /**
     * DH's chunk render distance, in BLOCKS, or 0 if it cannot be read.
     *
     * <p>{@code graphics().chunkRenderDistance()} is in CHUNKS; a chunk is 16 blocks.
     */
    public static int blocks() {
        try {
            final Integer chunks = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
            if (chunks == null || chunks <= 0) {
                return 0;
            }
            return chunks * 16;
        } catch (final LinkageError e) {
            // A LinkageError is NOT "DH is not up yet" -- it means the DH that IS installed does not have
            // the config API we compiled against. This is the other first-contact call into DH (alongside
            // DhTarget.inject's overwriteChunkDataAsync), so it is where that mismatch surfaces. Rule DH
            // out for the session -- loudly, once -- and let voxy carry on.
            DhTarget.disable(e);
            return 0;
        } catch (final RuntimeException e) {
            // DH is present and link-compatible but not initialized yet (DhApi.Delayed.* is still null).
            // Not fatal, not a mismatch: fall back to the default rather than guess.
            return 0;
        }
    }
}
