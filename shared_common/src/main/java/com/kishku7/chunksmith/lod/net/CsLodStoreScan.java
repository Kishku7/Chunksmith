package com.kishku7.chunksmith.lod.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Do we actually have anything to serve?" -- the one honest answer, for the whole LOD server.
 *
 * <p>A store DIRECTORY is not data. A pregen creates {@code <world>/chunksmith/lod/<dim>/} the moment it
 * starts and only fills it minutes later, so "the directory exists" made the server advertise a dimension it
 * could not serve a single region for, and issue a backchannel token to go with it (the "1 live token, 0
 * files" report). A dimension is SERVABLE when it holds at least one region file, and not before.
 *
 * <p>Also the transition detector behind the store-availability notice: a player who joined before the
 * pregen ran got an empty dimension list and stood down for the whole session. Poll this -- cheaply, and
 * only while somebody is waiting -- and the moment the first region lands we can tell them.
 */
public final class CsLodStoreScan {

    /** The extension every region file in the store carries. */
    public static final String REGION_SUFFIX = ".cslod";

    /**
     * How long a region file must sit untouched before we will serve it.
     *
     * <p><b>A region the pregen is still writing is not a region.</b> The store keeps the region file OPEN
     * and APPENDS to it as chunks complete, rewriting header slots as it goes, so a snapshot taken mid-write
     * has slots pointing past the end of the file. Serve that and the client downloads a file it cannot fully
     * read: it recovers (it takes the chunks that are there and re-fetches later, because the hash will have
     * moved on) but it logs an EOF on the way and it got a fraction of the region. Latent until the
     * store-availability notice started looking at the store DURING a pregen.
     *
     * <p>Ten seconds. A region is a thousand chunks and takes far longer than that to write, so a file that
     * has not moved in ten seconds is one the generator has finished with. Wrong in the safe direction costs
     * ten seconds of latency, which nobody can perceive; wrong the other way costs the EOF above.
     */
    public static final long SETTLE_MILLIS = 10_000L;

    private CsLodStoreScan() {
    }

    /**
     * Is this region file finished -- has the writer left it alone long enough that what we would hand a
     * client is what is actually in it? A file we cannot stat is treated as NOT settled.
     */
    public static boolean isSettled(final Path file, final long nowMillis) {
        try {
            return nowMillis - Files.getLastModifiedTime(file).toMillis() >= SETTLE_MILLIS;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Does this dimension directory hold at least one FINISHED region file? Stops at the FIRST match -- it
     * never lists a whole store. A directory that is missing, not a directory, or unreadable answers "no".
     */
    public static boolean hasData(final Path dimensionDir, final long nowMillis) {
        if (dimensionDir == null || !Files.isDirectory(dimensionDir)) {
            return false;
        }
        try (var entries = Files.list(dimensionDir)) {
            return entries.anyMatch(file -> isRegionFile(file) && isSettled(file, nowMillis));
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * The subset of these dimension directories we can actually serve, by directory NAME, in the order given.
     * The names are what goes on the wire in the server hello, and they are exactly the directory names the
     * store writes, so the client can turn one straight back into a request path.
     */
    public static List<String> servable(final List<Path> dimensionDirs, final long nowMillis) {
        final List<String> names = new ArrayList<>();
        if (dimensionDirs == null) {
            return names;
        }
        for (final Path dir : dimensionDirs) {
            if (hasData(dir, nowMillis)) {
                names.add(dir.getFileName().toString());
            }
        }
        return names;
    }

    /** Is this one of ours, and a real file? */
    public static boolean isRegionFile(final Path file) {
        return file.getFileName().toString().endsWith(REGION_SUFFIX) && Files.isRegularFile(file);
    }
}
