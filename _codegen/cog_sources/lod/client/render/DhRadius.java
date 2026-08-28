package com.kishku7.chunksmith.lod.client.render;

import com.seibel.distanthorizons.api.DhApi;

public final class DhRadius {

    private DhRadius() {
    }

    /** DH's render distance in BLOCKS, or 0 if unreadable. {@code chunkRenderDistance()} is CHUNKS x 16. */
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
