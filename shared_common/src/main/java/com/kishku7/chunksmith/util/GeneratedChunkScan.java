package com.kishku7.chunksmith.util;

import com.kishku7.chunksmith.nbt.StringTag;
import com.kishku7.chunksmith.nbt.TagType;
import com.kishku7.chunksmith.nbt.util.Chunk;
import com.kishku7.chunksmith.nbt.util.ChunkFilter;
import com.kishku7.chunksmith.nbt.util.RegionFile;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Learn which chunks a selection ALREADY has, in bulk, before the pre-gen dispatches anything.
 *
 * <p><b>Why this exists.</b> {@link RegionCache.WorldState} is an in-memory bitmap and it starts COLD:
 * {@code setGenerated} is only called for chunks THIS run generated. So on the run that matters --
 * restart the server, re-run a selection over ground you already pregenerated -- every chunk falls
 * through to the per-chunk asynchronous {@code world.isChunkGenerated} call, taking a dispatch slot and
 * riding the throttle just to be told "already there". Measured on a 5929-chunk selection that was
 * 100 percent already generated: about seven seconds to decide there was nothing to do. That is linear,
 * so a resumed hundred-thousand-chunk selection spends MINUTES learning what a handful of file headers
 * would have said (mod_support #17).
 *
 * <p><b>What it does instead.</b> A region file's header describes all 1024 of its chunks, so a
 * selection costs one read per REGION rather than one round-trip per CHUNK -- about nine files instead
 * of 5929 futures. Only chunks the header says exist are decompressed, and only as far as {@code Status}
 * ({@link ChunkFilter} makes the reader stop there).
 *
 * <p><b>Only {@code minecraft:full} counts</b> -- a chunk at an earlier status is NOT generated as far
 * as a pre-gen is concerned, and marking it so would silently skip half-built ground (the same class of
 * failure as mod_support #13). And it fails SOFT: anything unreadable -- a region file mid-write, an
 * unknown compression scheme, an oversized chunk in a sidecar -- is left unseeded and takes today's path.
 */
public final class GeneratedChunkScan {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Chunksmith");

    private GeneratedChunkScan() {
    }

    /**
     * Seed {@code state} with every {@code minecraft:full} chunk inside the given chunk box.
     *
     * @return how many chunks were marked generated
     */
    public static long seed(final Path regionDirectory,
                            final RegionCache.WorldState state,
                            final int minChunkX, final int minChunkZ,
                            final int maxChunkX, final int maxChunkZ) {
        final File dir = regionDirectory.toFile();
        final File[] files = dir.listFiles((d, name) -> name.startsWith("r.") && name.endsWith(".mca"));
        if (files == null || files.length == 0) {
            return 0L;
        }

        // Region bounds derived from the chunk box: 32 chunks to a region, floor-divided so negative
        // coordinates land in the right region rather than being truncated toward zero.
        final int minRegionX = minChunkX >> 5;
        final int minRegionZ = minChunkZ >> 5;
        final int maxRegionX = maxChunkX >> 5;
        final int maxRegionZ = maxChunkZ >> 5;

        long seeded = 0L;
        int scanned = 0;
        int unreadable = 0;

        for (final File file : files) {
            final int[] coords = regionCoordinates(file.getName());
            if (coords == null) {
                continue;
            }
            if (coords[0] < minRegionX || coords[0] > maxRegionX
                    || coords[1] < minRegionZ || coords[1] > maxRegionZ) {
                continue;   // wholly outside the selection: never opened
            }
            try {
                final RegionFile region = new RegionFile(file, ChunkFilter.of(TagType.STRING, "Status"));
                scanned++;
                for (final Chunk chunk : region.getChunks()) {
                    final int cx = chunk.getX();
                    final int cz = chunk.getZ();
                    if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) {
                        continue;
                    }
                    if (isFull(chunk)) {
                        state.setGenerated(cx, cz);
                        seeded++;
                    }
                }
            } catch (final Throwable t) {
                // Deliberately Throwable: the region reader throws UnsupportedOperationException on a
                // compression scheme it does not know, and unseeded just means "decide it the slow way".
                unreadable++;
            }
        }

        if (unreadable > 0) {
            LOGGER.info("Chunksmith: pre-scan read " + scanned + " region file(s), " + unreadable
                    + " unreadable (those chunks will be checked individually as before)");
        }
        return seeded;
    }

    /** {@code r.<x>.<z>.mca} -> {x, z}, or null if the name is not one. */
    private static int[] regionCoordinates(final String name) {
        final int end = name.indexOf(".mca");
        if (end < 2) {
            return null;
        }
        final String middle = name.substring(2, end);
        final int dot = middle.indexOf('.');
        if (dot < 1) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(middle.substring(0, dot)),
                    Integer.parseInt(middle.substring(dot + 1))
            };
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static boolean isFull(final Chunk chunk) {
        return Optional.ofNullable(chunk.getData())
                .filter(StringTag.class::isInstance)
                .map(StringTag.class::cast)
                .map(StringTag::value)
                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                .orElse(false);
    }
}
