package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the SERVER said about each region we hold -- the client's side of the cache check.
 *
 * <p><b>Why this class had to exist.</b> Until 3.1.0-beta-4 the region hash was a CRC32 of the file's
 * CONTENTS, so both ends could compute it independently: the server CRC'd its copy, the client CRC'd its
 * copy, and equal meant cached. That symmetry is exactly what made it a server killer -- the server was
 * reading every region file in the client's radius, on the tick thread, on every index request (see
 * {@code CsLodRegionHash}). The token is now derived from the SERVER's (mtime, size), which the client
 * cannot reproduce: the mtime of the client's copy is when the CLIENT wrote it. So the token is now OPAQUE
 * to the client, and the client's job is no longer to recompute it but to REMEMBER it.
 *
 * <p>That is all this is: a tiny sidecar, one per dimension, recording {@code (x, z) -> (token, size)} for
 * every region we have successfully stored, exactly as the server described it at the time. The cache check
 * becomes a map lookup plus one {@code size()} stat -- and the client stops reading its own store too. That
 * is not a side benefit: the client was doing the SAME 340-file, 1.5 GB {@code readAllBytes} sweep the
 * server was doing, on every index, in {@code CsLodCache.have} and {@code CsLodDownloader.haveAlready}. The
 * bug had two halves and only one of them was reported.
 *
 * <p><b>Format.</b> One line per region, {@code x,z=token,size}, plain ASCII, written atomically via a
 * {@code .part} file and a move -- the same discipline the region files themselves use, so a manifest torn
 * by a crash can never be half-read. Unknown or malformed lines are SKIPPED, not fatal: the worst a corrupt
 * manifest can do is make us re-download regions we already had, which is the safe direction.
 *
 * <p><b>Upgrading from 3.1.0-beta-3.</b> An existing client store has region files but no manifest. Every
 * region therefore reads as "not cached" and is fetched once, over the backchannel, on the first index of
 * the first join. For a 340-region / 1.5 GB store on a LAN that is a few seconds; it happens exactly once.
 * Re-downloading is the correct thing to do when we genuinely cannot vouch for what is on disk.
 *
 * <p>Thread-safe. The downloader writes it from four parallel fetch threads, the in-band reassembler writes
 * it from the client thread, and the sync poll reads it from its own.
 */
public final class CsLodManifest {

    /** The sidecar's name, inside the dimension directory, next to the regions it describes. */
    static final String FILE_NAME = ".manifest";

    /** What the server told us about one region we hold. */
    public record Entry(long hash, long sizeBytes) {
    }

    private final Path file;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    private CsLodManifest(final Path file) {
        this.file = file;
    }

    /**
     * Open (or start) the manifest for one dimension of one server's store.
     *
     * <p>A store root + a server-supplied dimension id, gated through {@link CsLodStore} like every other
     * consumer of that field. Returns null when the id is malformed -- the caller must then refuse the
     * whole operation, exactly as the downloader and the injector do.
     */
    public static CsLodManifest open(final Path storeRoot, final String dimension) {
        final Path dir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dir == null) {
            return null;
        }
        final CsLodManifest manifest = new CsLodManifest(dir.resolve(FILE_NAME));
        manifest.load();
        return manifest;
    }

    /** Record what the server said about a region we have just stored. Call AFTER the file is in place. */
    public void put(final int regionX, final int regionZ, final long hash, final long sizeBytes) {
        this.entries.put(key(regionX, regionZ), new Entry(hash, sizeBytes));
    }

    /** What the server said about this region last time we stored it, or null if we have never stored it. */
    public Entry get(final int regionX, final int regionZ) {
        return this.entries.get(key(regionX, regionZ));
    }

    /** Forget a region -- it is gone from disk, or it failed to store. */
    public void remove(final int regionX, final int regionZ) {
        this.entries.remove(key(regionX, regionZ));
    }

    public int size() {
        return this.entries.size();
    }

    /**
     * Do we hold this region, exactly as the server currently describes it?
     *
     * <p>Three questions, cheapest first, and NONE of them reads the file: do we have a manifest entry for
     * it; does that entry carry the token the server is advertising NOW; and is the file still on disk at
     * the length we recorded. The last one is the only syscall, and it is what catches a region the player
     * (or a disk, or a cleanup script) deleted or truncated underneath us -- which is precisely what the
     * periodic sync is meant to heal.
     *
     * @param dimensionDir the directory the regions live in -- ALREADY gated through {@link CsLodStore}
     */
    public boolean holds(final Path dimensionDir, final CsLodMessages.RegionEntry advertised) {
        final Entry mine = get(advertised.regionX(), advertised.regionZ());
        if (mine == null || mine.hash() != advertised.hash()) {
            return false;
        }
        // A zero token means the server declined to describe the region (an in-band request echoes
        // coordinates only). We cannot vouch for a file we were never told anything about.
        if (advertised.hash() == 0L) {
            return false;
        }
        final Path region = dimensionDir.resolve(
                "r." + advertised.regionX() + "." + advertised.regionZ() + ".cslod");
        try {
            return Files.isRegularFile(region) && Files.size(region) == mine.sizeBytes();
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Fold the regions we ACTUALLY HOLD, out of the ones the server last told us about, into the same
     * (count, aggregate) shape the server folds its own set into.
     *
     * <p>This is the client half of the sync compare, and the set it folds over is deliberately the
     * SERVER'S LAST INDEX, not a listing of our own directory. That is what makes an idle poll idle: the
     * server excludes regions its pregen is still writing (they are not settled), and if we folded over our
     * own directory we would keep counting our stale copies of them, disagree with the server forever, and
     * pull a full index every single interval for the whole length of a pregen. Folding over the last index
     * means both sides are describing the same question, so "nothing changed" really does compare equal.
     *
     * <p>A region in the index that we do NOT hold -- never fetched, deleted since, truncated, or whose
     * token has moved on -- simply does not contribute, so the aggregate AND the count both drop and the
     * mismatch is detected. That single property covers all three of "the server grew", "the client lost
     * regions", and "a region changed".
     *
     * @param dimensionDir the directory the regions live in -- ALREADY gated through {@link CsLodStore}
     * @param advertised   the entries of the last index the server sent us
     */
    public CsLodSummary.Snapshot fold(final Path dimensionDir,
                                      final List<CsLodMessages.RegionEntry> advertised) {
        int count = 0;
        long aggregate = 0L;
        for (final CsLodMessages.RegionEntry entry : advertised) {
            if (!holds(dimensionDir, entry)) {
                continue;
            }
            count++;
            aggregate = CsLodSummary.fold(aggregate, entry.regionX(), entry.regionZ(), entry.hash());
        }
        return new CsLodSummary.Snapshot(count, aggregate);
    }

    /**
     * Write the manifest out. Atomic: a {@code .part} plus a move, so a crash mid-write leaves either the
     * old manifest or the new one, never a torn one.
     *
     * <p>Failure is logged by the caller and otherwise survivable -- a manifest we could not write means we
     * re-download those regions next session, not that we lose them.
     */
    public void save() throws IOException {
        final List<String> lines = new ArrayList<>(this.entries.size());
        for (final Map.Entry<Long, Entry> entry : this.entries.entrySet()) {
            final long packed = entry.getKey();
            final int regionX = (int) (packed >> 32);
            final int regionZ = (int) packed;
            lines.add(regionX + "," + regionZ + "=" + entry.getValue().hash() + ","
                    + entry.getValue().sizeBytes());
        }
        Files.createDirectories(this.file.getParent());
        final Path temp = this.file.resolveSibling(FILE_NAME + ".part");
        Files.write(temp, lines, StandardCharsets.US_ASCII);
        Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Read whatever is there. A missing manifest is not an error -- it is a store that predates this
     * mechanism, or a brand new one, and it simply means we hold nothing we can vouch for.
     */
    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        final List<String> lines;
        try {
            lines = Files.readAllLines(this.file, StandardCharsets.US_ASCII);
        } catch (final IOException e) {
            // Unreadable manifest == no manifest. We re-fetch; we never guess.
            return;
        }
        for (final String line : lines) {
            parse(line);
        }
    }

    /** {@code x,z=token,size}. Anything else is skipped in silence -- see the class doc. */
    private void parse(final String line) {
        final int equals = line.indexOf('=');
        if (equals <= 0) {
            return;
        }
        final String[] coords = line.substring(0, equals).split(",", -1);
        final String[] values = line.substring(equals + 1).split(",", -1);
        if (coords.length != 2 || values.length != 2) {
            return;
        }
        try {
            this.entries.put(
                    key(Integer.parseInt(coords[0].trim()), Integer.parseInt(coords[1].trim())),
                    new Entry(Long.parseLong(values[0].trim()), Long.parseLong(values[1].trim())));
        } catch (final NumberFormatException ignored) {
            // A line we cannot read is a region we cannot vouch for. Re-download it; do not crash over it.
        }
    }

    private static long key(final int regionX, final int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }
}
