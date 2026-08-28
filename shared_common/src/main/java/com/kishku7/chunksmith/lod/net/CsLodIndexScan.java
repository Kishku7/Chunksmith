package com.kishku7.chunksmith.lod.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Which regions can this player see, and how fresh is each one?" -- the one answer, for every platform.
 *
 * <p>A pure function of a directory and a position: no game object, no state, no logging. The mod loaders
 * and the Bukkit/Paper plugin need the same answer, and only the reading of a player's position and
 * dimension ever differed. The plugin grew an index responder for mod_support #18, and copying the scan out
 * of the mod's {@code CsLodServerNet} would have created two definitions of "in range" that must agree
 * forever with no test that they do -- they would drift, and the symptom would be a client fetching the
 * wrong regions on one platform only.
 *
 * <p><b>The consistency rule this file inherits.</b> The full index and the periodic sync summary are
 * computed over the same set. If they were not, an idle poll would find a difference, pull a full index,
 * discover nothing to fetch, and do it all again on the next interval, forever. So there is one scan, and a
 * summary is that scan folded to two numbers -- never a second, cheaper walk.
 *
 * <p>Per region, cheapest test first: the name must parse as one of ours ({@code r.<x>.<z>.cslod}, a string
 * test); it must be {@link #inRange} of the player (integer arithmetic -- doing this before the stat is the
 * difference between statting 81 files and statting all 340); one {@code readAttributes} gives mtime and
 * size together, one {@code statx} rather than two; and it must be settled, because a region the pregen is
 * still appending to has header slots pointing past the end of what a client would receive (ten seconds
 * untouched, see {@link CsLodStoreScan}).
 *
 * <p>Then sorted nearest first and truncated to the caps. Sorting is what makes the truncation
 * deterministic -- {@code Files.list} order is whatever the filesystem says, so an un-sorted cap would
 * return a different subset on each call and the summary would never match the index.
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
     * Everything the scan needs, and nothing a game tick can mutate underneath it. Callers on a loader read
     * these off the player synchronously on the main thread and hand the record to a worker; the record is
     * the thread boundary.
     */
    public record Request(String dimension, int px, int pz, int radiusBlocks) {
    }

    /**
     * The regions to serve, plus what was dropped getting there.
     *
     * <p>{@code found} is how many passed every filter before the caps, so a caller can say "capped at 4096
     * of 9000" in its own logger. Returning the number rather than logging keeps this class free of a logging
     * dependency: shared_common is compiled into a plugin jar and three loader jars, which do not agree on a
     * logger.
     */
    public record Result(List<CsLodMessages.RegionEntry> regions, int found, long bytes) {

        /** True when the caps dropped something the player could otherwise have had. */
        public boolean capped() {
            return regions.size() < found;
        }
    }

    private CsLodIndexScan() {
    }

    /**
     * Scan one dimension directory for the regions in range of a position. Never reads a byte of any region
     * file. A directory that does not exist is not an error: it is a store that has not been pregenerated
     * yet, and the honest answer is an empty list.
     *
     * @param dimensionDir the directory holding {@code r.<x>.<z>.cslod} files for one dimension
     * @param nowMillis    the clock, injected so the settle rule is testable
     */
    public static Result scan(final Path dimensionDir, final Request request, final long nowMillis)
            throws IOException {
        final List<CsLodMessages.RegionEntry> found = new ArrayList<>();
        if (dimensionDir == null || !Files.isDirectory(dimensionDir)) {
            return new Result(List.of(), 0, 0L);
        }
        try (var files = Files.list(dimensionDir)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(CsLodStoreScan.REGION_SUFFIX)) {
                    continue;
                }
                final String[] parts = name.split("\\.");
                if (parts.length != 4) {
                    continue;
                }
                final int regionX;
                final int regionZ;
                try {
                    regionX = Integer.parseInt(parts[1]);
                    regionZ = Integer.parseInt(parts[2]);
                } catch (final NumberFormatException ignored) {
                    continue;   // not one of ours
                }
                if (!inRange(request, regionX, regionZ)) {
                    continue;
                }
                final BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(file, BasicFileAttributes.class);
                } catch (final IOException e) {
                    continue;   // it went away under us; the client re-asks
                }
                if (!attrs.isRegularFile()) {
                    continue;
                }
                // A file we cannot vouch for is a file we do not serve -- the same rule as
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

    /** Fold a scan result to the two numbers a sync poll compares. */
    public static long aggregate(final List<CsLodMessages.RegionEntry> regions) {
        long aggregate = 0L;
        for (final CsLodMessages.RegionEntry entry : regions) {
            aggregate = CsLodSummary.fold(aggregate, entry.regionX(), entry.regionZ(), entry.hash());
        }
        return aggregate;
    }

    /** Apply both caps -- the region count and the byte budget -- to a nearest-first list. */
    private static Result cap(final List<CsLodMessages.RegionEntry> found) {
        long bytes = 0L;
        for (int i = 0; i < found.size(); i++) {
            final long next = bytes + found.get(i).sizeBytes();
            if (i >= MAX_REGIONS || next > MAX_BYTES) {
                return new Result(List.copyOf(found.subList(0, i)), found.size(), bytes);
            }
            bytes = next;
        }
        return new Result(List.copyOf(found), found.size(), bytes);
    }

    /**
     * Is this region within the radius the client's renderer can actually draw, measured from the
     * player? The client tells us its configured LOD distance in the handshake and we follow it, lower
     * or higher: past it is bandwidth spent on terrain nobody sees, short of it leaves visible holes.
     * A region is 512 blocks square, so the test is against the region's box and not its corner -- one
     * only partly inside the radius still contains terrain the player can see.
     */
    public static boolean inRange(final Request request, final int regionX, final int regionZ) {
        return distanceSquared(request, regionX, regionZ)
                <= (long) request.radiusBlocks() * request.radiusBlocks();
    }

    /** Squared distance from the player to the nearest point of a region's box. Also the sort key. */
    public static long distanceSquared(final Request request, final int regionX, final int regionZ) {
        final int minX = regionX * REGION_BLOCKS;
        final int minZ = regionZ * REGION_BLOCKS;
        final int maxX = minX + REGION_BLOCKS - 1;
        final int maxZ = minZ + REGION_BLOCKS - 1;

        final int dx = Math.max(0, Math.max(minX - request.px(), request.px() - maxX));
        final int dz = Math.max(0, Math.max(minZ - request.pz(), request.pz() - maxZ));
        return (long) dx * dx + (long) dz * dz;
    }
}
