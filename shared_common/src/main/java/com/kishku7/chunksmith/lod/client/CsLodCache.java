package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodMessages;

import java.nio.file.Path;

/**
 * Tells the fetchers which regions we are missing. Whether regions arrive over HTTP at 55 MB/s or dribble
 * down the game connection, we ask only for what we lack; a re-join fetches nothing.
 *
 * <p>It used to read the whole region file and CRC32 it against the server's content hash -- the
 * client's half of the bug that took a live server to 100% RAM. On a 340-region store that was ~1.5 GB
 * slurped off its own disk into multi-megabyte (G1-humongous) byte arrays on every index, and an index
 * arrives every five seconds while the player travels. The freshness token is opaque now (see
 * {@code CsLodRegionHash}) and the client simply remembers what the server told it about each region it
 * stored ({@link CsLodManifest}). Nothing is read.
 */
public final class CsLodCache {

    private CsLodCache() {
    }

    /** True when our local copy is the one the server is currently advertising. */
    public static boolean have(final Path storeRoot, final String dimension,
                               final CsLodManifest manifest, final CsLodMessages.RegionEntry entry) {
        if (manifest == null) {
            return false;
        }
        // The dimension is server-supplied; gate it before it becomes a path (D20: every consumer, not
        // one). A malformed id is treated as "not cached", and the caller's fetch path refuses it too.
        Path dimDir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dimDir == null) {
            return false;
        }
        return manifest.holds(dimDir, entry);
    }
}
