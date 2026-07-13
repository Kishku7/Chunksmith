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
 * starts and only fills it minutes later, so "the directory exists" was answering a question nobody asked:
 * it made the server advertise a dimension it could not serve a single region for, and issue a backchannel
 * token to go with it (the "1 live token, 0 files" report). A dimension is SERVABLE when it holds at least
 * one region file, and not before.
 *
 * <p>This is also the transition detector behind the store-availability notice: a player who joined before
 * the pregen ran got an empty dimension list and stood down for the whole session. Poll this -- cheaply,
 * and only while somebody is actually waiting -- and the moment the first region lands we can tell them.
 *
 * <p>MC-agnostic on purpose, like everything else in this package: it takes plain {@link Path}s and returns
 * plain names, so it can be unit-tested against a temp directory with no Minecraft in sight.
 */
public final class CsLodStoreScan {

    /** The extension every region file in the store carries. */
    public static final String REGION_SUFFIX = ".cslod";

    /**
     * How long a region file must sit untouched before we will serve it.
     *
     * <p><b>A region the pregen is still writing is not a region.</b> The store keeps the region file OPEN
     * and APPENDS to it as chunks complete, rewriting header slots as it goes -- so a snapshot taken mid-write
     * has slots pointing past the end of the file. Serve that, and the client downloads a file it cannot fully
     * read: it recovers (it takes the chunks that are there and re-fetches later, because the content hash
     * will have moved on), but it logs an EOF on the way and it got a fraction of the region.
     *
     * <p>This was always latent -- a travel refresh that landed mid-pregen hit it -- and it stayed hidden
     * because nothing used to look at the store DURING a pregen. The store-availability notice does exactly
     * that, by design, so it has to be handled rather than inherited.
     *
     * <p>Ten seconds. A region is a thousand chunks and takes far longer than that to write, so a file that
     * has not moved in ten seconds is one the generator has finished with and moved on from. The cost of
     * being wrong in the safe direction is that a freshly-finished region shows up ten seconds late, which
     * nobody can perceive; the cost of being wrong the other way is the EOF above.
     */
    public static final long SETTLE_MILLIS = 10_000L;

    private CsLodStoreScan() {
    }

    /**
     * Is this region file finished -- i.e. has the writer left it alone long enough that what we would hand a
     * client is what is actually in it?
     *
     * <p>A file we cannot stat is treated as NOT settled: if we cannot tell, we do not serve it.
     */
    public static boolean isSettled(final Path file, final long nowMillis) {
        try {
            return nowMillis - Files.getLastModifiedTime(file).toMillis() >= SETTLE_MILLIS;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Does this dimension directory hold at least one FINISHED region file?
     *
     * <p>Stops at the FIRST match -- it never lists a whole store. On a directory that does not exist, is
     * not a directory, or cannot be read, the answer is simply "no": an unreadable store is one we cannot
     * serve, which is the same outcome as an empty one.
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
     * The subset of these dimension directories we can actually serve, by directory NAME, in the order
     * given.
     *
     * <p>The names are what goes on the wire in the server hello, and they are exactly the directory names
     * the store writes -- so the client can turn one straight back into a request path.
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
