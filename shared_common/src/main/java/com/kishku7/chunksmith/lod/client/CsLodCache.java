package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodMessages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * The "do I already have this?" check, shared by BOTH transports.
 *
 * <p>The cache rule does not change just because the transport did: whether the regions arrive over HTTP at
 * 55 MB/s or dribble down the game connection, we ask only for what we are missing. A re-join fetches
 * nothing.
 */
public final class CsLodCache {

    private CsLodCache() {
    }

    /** True when our local copy matches the server's, by size and content hash. */
    public static boolean have(final Path storeRoot, final String dimension,
                               final CsLodMessages.RegionEntry entry) {
        // The dimension is server-supplied; gate it before it becomes a path (D20 -- every consumer, not
        // one). A malformed id is treated as "not cached", and the caller's fetch path refuses it too.
        final Path dimDir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dimDir == null) {
            return false;
        }
        final Path file = dimDir.resolve("r." + entry.regionX() + "." + entry.regionZ() + ".cslod");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (entry.sizeBytes() > 0 && Files.size(file) != entry.sizeBytes()) {
                return false;
            }
            if (entry.hash() == 0L) {
                // The server did not give us a hash (an in-band request echoes coordinates only), so size
                // is all we have. Better to re-fetch than to trust a file we cannot verify.
                return false;
            }
            final CRC32 crc = new CRC32();
            crc.update(Files.readAllBytes(file));
            return crc.getValue() == entry.hash();
        } catch (final IOException e) {
            return false;
        }
    }
}
