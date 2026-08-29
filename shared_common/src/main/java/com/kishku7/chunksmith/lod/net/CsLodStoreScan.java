package com.kishku7.chunksmith.lod.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers whether a dimension has anything to serve yet. It is servable when it
 * holds at least one region file, and not before.
 *
 * <p>A pregen creates {@code <world>/chunksmith/lod/<dim>/} the moment it starts
 * and only fills it minutes later, so testing "the directory exists" made the
 * server advertise a dimension it could not serve a single region for, and issue
 * a backchannel token to go with it (the "1 live token, 0 files" report).
 *
 * <p>Also the transition detector behind the store-availability notice: a player
 * who joined before the pregen ran got an empty dimension list and stood down for
 * the whole session. Poll this (cheaply, and only while somebody is waiting) and
 * the moment the first region lands we can tell them.
 */
public final class CsLodStoreScan {

    /** The extension every region file in the store carries. */
    public static final String REGION_SUFFIX = ".cslod";

    /**
     * Ten seconds untouched before a region file is served.
     *
     * <p>The store keeps the file open and appends to it as chunks complete,
     * rewriting header slots as it goes, so a snapshot taken mid-write has slots
     * pointing past the end of the file. A client that downloads one recovers: it
     * takes the chunks that are there and re-fetches later, because the hash will
     * have moved on, but it logs an EOF on the way and it got a fraction of the
     * region. Latent until the store-availability notice started looking at the
     * store during a pregen.
     *
     * <p>A region is a thousand chunks and takes far longer than ten seconds to
     * write, so a file that has not moved in that long is one the generator has
     * finished with. Erring long costs ten seconds of latency; erring short costs
     * the EOF above.
     */
    public static final long SETTLE_MILLIS = 10_000L;

    private CsLodStoreScan() {
    }

    /**
     * Checks whether this region file is finished. Has the writer left it alone
     * long enough that what we would hand a client is what is actually in it? A
     * file we cannot stat is treated as not settled.
     *
     * @return true when the writer has left the file alone long enough to serve it
     */
    public static boolean isSettled(Path file, long nowMillis) {
        try {
            return nowMillis - Files.getLastModifiedTime(file).toMillis() >= SETTLE_MILLIS;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Checks whether this dimension directory holds at least one finished region
     * file. Stops at the first match, so it never lists a whole store. A
     * directory that is missing or unreadable answers "no".
     */
    public static boolean hasData(Path dimensionDir, long nowMillis) {
        if (dimensionDir == null || !Files.isDirectory(dimensionDir)) {
            return false;
        }
        try (var entries = Files.list(dimensionDir)) {
            return entries.anyMatch(file -> isRegionFile(file) && isSettled(file, nowMillis));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the subset of these dimension directories we can serve, by
     * directory name, in the order given. The names are what goes on the wire in
     * the server hello, and they are exactly the directory names the store
     * writes, so the client can turn one straight back into a request path.
     *
     * @return the directory names, in the order given, that hold servable regions
     */
    public static List<String> servable(List<Path> dimensionDirs, long nowMillis) {
        List<String> names = new ArrayList<>();
        if (dimensionDirs == null) {
            return names;
        }
        for (Path dir : dimensionDirs) {
            if (hasData(dir, nowMillis)) {
                names.add(dir.getFileName().toString());
            }
        }
        return names;
    }

    /** Checks whether this is one of ours, and a real file. */
    public static boolean isRegionFile(Path file) {
        return file.getFileName().toString().endsWith(REGION_SUFFIX) && Files.isRegularFile(file);
    }
}
