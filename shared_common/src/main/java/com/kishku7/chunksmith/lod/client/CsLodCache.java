package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodMessages;

import java.nio.file.Path;

/**
 * The "do I already have this?" check, shared by BOTH transports.
 *
 * <p>The cache rule does not change just because the transport did: whether the regions arrive over HTTP at
 * 55 MB/s or dribble down the game connection, we ask only for what we are missing. A re-join fetches
 * nothing.
 *
 * <p><b>What this used to do, and why it stopped.</b> It read the whole region file and CRC32'd it, to
 * compare against the server's content hash. That is the client's half of the bug that took a live server to
 * 100% RAM: on a 340-region store the client was slurping ~1.5 GB off its own disk, into multi-megabyte
 * (G1-humongous) byte arrays, on EVERY index -- and an index arrives every five seconds while the player
 * travels. The server's half was reported because a server dies loudly; the client's half was not, because a
 * client with an 8 GB heap and no pregen running merely stutters.
 *
 * <p>Now the freshness token is opaque (see {@code CsLodRegionHash}) and the client simply remembers what the
 * server told it about each region it stored ({@link CsLodManifest}). The check is a map lookup and one
 * {@code size()} stat. Nothing is read.
 */
public final class CsLodCache {

    private CsLodCache() {
    }

    /**
     * True when our local copy is the one the server is currently advertising.
     *
     * @param storeRoot the client's store for this server
     * @param dimension the server-supplied dimension id -- gated here, as every consumer of it must be
     * @param manifest  what the server said about the regions we hold, from {@link CsLodManifest#open}
     */
    public static boolean have(final Path storeRoot, final String dimension,
                               final CsLodManifest manifest, final CsLodMessages.RegionEntry entry) {
        if (manifest == null) {
            return false;
        }
        // The dimension is server-supplied; gate it before it becomes a path (D20 -- every consumer, not
        // one). A malformed id is treated as "not cached", and the caller's fetch path refuses it too.
        final Path dimDir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dimDir == null) {
            return false;
        }
        return manifest.holds(dimDir, entry);
    }
}
