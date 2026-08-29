package com.kishku7.chunksmith.lod.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Given a store directory and a player's position, returns the regions in range
 * and how fresh each one is. A pure function: no game object, no state, no
 * logging.
 *
 * <p>The mod loaders and the Bukkit/Paper plugin need the same answer, and only
 * the reading of a player's position and dimension ever differed. The plugin
 * grew an index responder for mod_support #18, and copying the scan out of the
 * mod's {@code CsLodServerNet} would have created two definitions of "in range"
 * that must agree forever with no test that they do: they would drift, and the
 * symptom would be a client fetching the wrong regions on one platform only.
 *
 * <p><b>The consistency rule this file inherits.</b> The full index and the
 * periodic sync summary are computed over the same set. If they were not, an
 * idle poll would find a difference, pull a full index, discover nothing to
 * fetch, and do it all again on the next interval, forever. So there is one
 * scan, and a summary is that scan folded to two numbers, never a second,
 * cheaper walk.
 *
 * <p>Per region, cheapest test first: the name must parse as one of ours
 * ({@code r.<x>.<z>.cslod}, a string test); it must be {@link #inRange} of the
 * player (integer arithmetic; doing this before the stat is the difference
 * between statting 81 files and statting all 340); one {@code readAttributes}
 * gives mtime and size together, one {@code statx} rather than two; and it must
 * be settled, because a region the pregen is still appending to has header
 * slots pointing past the end of what a client would receive (ten seconds
 * untouched, see {@link CsLodStoreScan}).
 *
 * <p>Then sorted nearest first and truncated to the caps. Sorting is what makes
 * the truncation deterministic: {@code Files.list} order is whatever the
 * filesystem says, so an un-sorted cap would return a different subset on each
 * call and the summary would never match the index.
 */
public final class CsLodIndexScan {

    /** A region is 32 chunks square. */
    public static final int REGION_BLOCKS = 512;

    /** Hard ceiling on how many regions one answer may list. */
    public static final int MAX_REGIONS = 4096;

    /**
     * Byte budget for one answer, and the cap that actually binds: 4096 regions of 4.6 MB is ~18 GB.
     */
    public static final long MAX_BYTES = 2L * 1024L * 1024L * 1024L;

    /**
     * Everything the scan needs, and nothing a game tick can mutate underneath
     * it. Callers on a loader read these off the player synchronously on the
     * main thread and hand the record to a worker; the record is the thread
     * boundary.
     */
    public record Request(String dimension, int px, int pz, int radiusBlocks) {
    }

    /**
     * The regions to serve, plus what was dropped getting there. {@code found}
     * is how many passed every filter before the caps, so a caller can say
     * "capped at 4096 of 9000" in its own logger. Returning the number rather
     * than logging keeps this class free of a logging dependency: shared_common
     * is compiled into a plugin jar and three loader jars, which do not agree
     * on a logger.
     */
    public record Result(List<CsLodMessages.RegionEntry> regions, int found, long bytes) {

        /** Returns true when the caps dropped something the player could otherwise have had. */
        public boolean capped() {
            return regions.size() < found;
        }
    }

    private CsLodIndexScan() {
    }

    /**
     * Scans one dimension directory for the regions in range of a position.
     * Never reads a byte of any region file. A directory that does not exist is
     * not an error. It is a store that has not been pregenerated yet, and the
     * honest answer is an empty list.
     *
     * @param dimensionDir the directory holding {@code r.<x>.<z>.cslod} files for one dimension
     * @param nowMillis    the clock, injected so the settle rule is testable
     */
    public static Result scan(Path dimensionDir, Request request, long nowMillis)
            throws IOException {
        List<CsLodMessages.RegionEntry> found = new ArrayList<>();
        if (dimensionDir == null || !Files.isDirectory(dimensionDir)) {
            return new Result(List.of(), 0, 0L);
        }
        try (var files = Files.list(dimensionDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(CsLodStoreScan.REGION_SUFFIX)) {
                    continue;
                }
                String[] parts = name.split("\\.");
                if (parts.length != 4) {
                    continue;
                }
                int regionX;
                int regionZ;
                try {
                    regionX = Integer.parseInt(parts[1]);
                    regionZ = Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                    continue;   // not one of ours
                }
                if (!inRange(request, regionX, regionZ)) {
                    continue;
                }
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(file, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue;   // it went away under us; the client re-asks
                }
                if (!attrs.isRegularFile()) {
                    continue;
                }
                // A file we cannot vouch for is a file we do not serve, the same rule as
                // CsLodStoreScan, answered from attributes we already have rather than a second stat.
                if (nowMillis - attrs.lastModifiedTime().toMillis() < CsLodStoreScan.SETTLE_MILLIS) {
                    continue;
                }
                found.add(new CsLodMessages.RegionEntry(regionX, regionZ,
                        CsLodRegionHash.of(attrs.lastModifiedTime().toMillis(), attrs.size()),
                        attrs.size()));
            }
        }

        found.sort(Comparator
                .comparingLong((CsLodMessages.RegionEntry e) ->
                        distanceSquared(request, e.regionX(), e.regionZ()))
                .thenComparingInt(CsLodMessages.RegionEntry::regionX)
                .thenComparingInt(CsLodMessages.RegionEntry::regionZ));

        return cap(found);
    }

    /** Folds a scan result to the two numbers a sync poll compares. */
    public static long aggregate(List<CsLodMessages.RegionEntry> regions) {
        long aggregate = 0L;
        for (CsLodMessages.RegionEntry entry : regions) {
            aggregate = CsLodSummary.fold(aggregate, entry.regionX(), entry.regionZ(), entry.hash());
        }
        return aggregate;
    }

    /** Applies both caps (the region count and the byte budget) to a nearest-first list. */
    private static Result cap(List<CsLodMessages.RegionEntry> found) {
        long bytes = 0L;
        for (int i = 0; i < found.size(); i++) {
            long next = bytes + found.get(i).sizeBytes();
            if (i >= MAX_REGIONS || next > MAX_BYTES) {
                return new Result(List.copyOf(found.subList(0, i)), found.size(), bytes);
            }
            bytes = next;
        }
        return new Result(List.copyOf(found), found.size(), bytes);
    }

    /**
     * Checks whether this region is within the radius the client's renderer can
     * actually draw, measured from the player. The client tells us its
     * configured LOD distance in the handshake and we follow it, lower or
     * higher. Past it is bandwidth spent on terrain nobody sees, and short of
     * it leaves visible holes. A region is 512 blocks square, so the test is
     * against the region's box and not its corner; one only partly inside the
     * radius still contains terrain the player can see.
     *
     * @return true when the region is inside the client's draw radius
     */
    public static boolean inRange(Request request, int regionX, int regionZ) {
        return distanceSquared(request, regionX, regionZ)
                <= (long) request.radiusBlocks() * request.radiusBlocks();
    }

    /** Returns the squared distance from the player to the nearest point of a region's box. The sort key. */
    public static long distanceSquared(Request request, int regionX, int regionZ) {
        int minX = regionX * REGION_BLOCKS;
        int minZ = regionZ * REGION_BLOCKS;
        int maxX = minX + REGION_BLOCKS - 1;
        int maxZ = minZ + REGION_BLOCKS - 1;

        int dx = Math.max(0, Math.max(minX - request.px(), request.px() - maxX));
        int dz = Math.max(0, Math.max(minZ - request.pz(), request.pz() - maxZ));
        return (long) dx * dx + (long) dz * dz;
    }
}
