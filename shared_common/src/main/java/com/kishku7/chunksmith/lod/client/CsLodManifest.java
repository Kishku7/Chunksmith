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
 * What the server said about each region we hold, the client's side of the cache check.
 *
 * <p>Until 3.1.0-beta-4 the region hash was a CRC32 of the file's contents, which both ends could
 * compute independently, and that symmetry is what made it a server killer (see
 * {@code CsLodRegionHash}). The token is now derived from the server's (mtime, size), which the client
 * cannot reproduce, so the client's job is to remember it rather than recompute it. The client
 * had the same bug in its own half: the same 340-file, 1.5 GB {@code readAllBytes} sweep, on every
 * index, in {@code CsLodCache.have} and {@code CsLodDownloader.haveAlready}.
 *
 * <p>Format: one line per region, {@code x,z=token,size}, plain ASCII, written atomically via a
 * {@code .part} file and a move. Malformed lines are skipped, not fatal; the worst a corrupt manifest
 * can do is make us re-download regions we already had.
 *
 * <p>Upgrading from 3.1.0-beta-3: an existing store has region files but no manifest, so every region
 * reads as "not cached" and is re-fetched once over the backchannel on the first index of the first join
 * (seconds for a 340-region / 1.5 GB store on a LAN).
 *
 * <p>Thread-safe: written by four parallel fetch threads and by the in-band reassembler on the client
 * thread, read by the sync poll.
 */
public final class CsLodManifest {

    /** The sidecar's name, inside the dimension directory, next to the regions it describes. */
    static final String FILE_NAME = ".manifest";

    public record Entry(long hash, long sizeBytes) {
    }

    private final Path file;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    private CsLodManifest(Path file) {
        this.file = file;
    }

    /**
     * Open (or start) the manifest for one dimension of one server's store. Returns null when the dimension
     * id is malformed. The caller must then refuse the whole operation, as the downloader and injector do.
     */
    public static CsLodManifest open(Path storeRoot, String dimension) {
        Path dir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dir == null) {
            return null;
        }
        CsLodManifest manifest = new CsLodManifest(dir.resolve(FILE_NAME));
        manifest.load();
        return manifest;
    }

    /** Record what the server said about a region we have just stored, once the file is in place. */
    public void put(int regionX, int regionZ, long hash, long sizeBytes) {
        this.entries.put(key(regionX, regionZ), new Entry(hash, sizeBytes));
    }

    public Entry get(int regionX, int regionZ) {
        return this.entries.get(key(regionX, regionZ));
    }

    public void remove(int regionX, int regionZ) {
        this.entries.remove(key(regionX, regionZ));
    }

    public int size() {
        return this.entries.size();
    }

    /**
     * Do we hold this region, exactly as the server currently describes it?
     *
     * <p>Three questions, cheapest first, and not one of them reads the file: do we have an entry; does it
     * carry the token the server advertises now; is the file still on disk at the length we recorded. The
     * last is the only syscall, and it is what catches a region deleted or truncated underneath us.
     *
     * @param dimensionDir the directory the regions live in -- already gated through {@link CsLodStore}
     */
    public boolean holds(Path dimensionDir, CsLodMessages.RegionEntry advertised) {
        Entry mine = get(advertised.regionX(), advertised.regionZ());
        if (mine == null || mine.hash() != advertised.hash()) {
            return false;
        }
        // A zero token means the server declined to describe the region (an in-band request echoes
        // coordinates only). We cannot vouch for a file we were never told anything about.
        if (advertised.hash() == 0L) {
            return false;
        }
        Path region = dimensionDir.resolve(
                "r." + advertised.regionX() + "." + advertised.regionZ() + ".cslod");
        try {
            return Files.isRegularFile(region) && Files.size(region) == mine.sizeBytes();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Fold the regions we actually hold, out of the ones the server last told us about, into the same
     * (count, aggregate) shape the server folds its own set into.
     *
     * <p>The set folded over is the server's last index, deliberately, and not a listing of our own
     * directory. The server excludes regions its pregen is still writing; folding over our own directory
     * would keep counting our stale copies of them, disagree forever, and pull a full index every interval
     * for the whole length of a pregen. A region in the index we do not hold simply does not contribute, so
     * the aggregate and the count both drop, which covers "the server grew", "the client lost regions" and
     * "a region changed" alike.
     *
     * @param advertised the entries of the last index the server sent us
     */
    public CsLodSummary.Snapshot fold(final Path dimensionDir,
                                      final List<CsLodMessages.RegionEntry> advertised) {
        int count = 0;
        long aggregate = 0L;
        for (CsLodMessages.RegionEntry entry : advertised) {
            if (!holds(dimensionDir, entry)) {
                continue;
            }
            count++;
            aggregate = CsLodSummary.fold(aggregate, entry.regionX(), entry.regionZ(), entry.hash());
        }
        return new CsLodSummary.Snapshot(count, aggregate);
    }

    /**
     * Write the manifest out, atomically; see the class doc. A manifest we could not write means we
     * re-download those regions next session, so failure is logged by the caller and otherwise survivable.
     */
    public void save() throws IOException {
        List<String> lines = new ArrayList<>(this.entries.size());
        for (Map.Entry<Long, Entry> entry : this.entries.entrySet()) {
            long packed = entry.getKey();
            int regionX = (int) (packed >> 32);
            int regionZ = (int) packed;
            lines.add(regionX + "," + regionZ + "=" + entry.getValue().hash() + ","
                    + entry.getValue().sizeBytes());
        }
        Files.createDirectories(this.file.getParent());
        Path temp = this.file.resolveSibling(FILE_NAME + ".part");
        Files.write(temp, lines, StandardCharsets.US_ASCII);
        Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Read whatever is there. A missing manifest is a store that predates this mechanism, or a brand new
     * one, and it means we hold nothing we can vouch for.
     */
    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(this.file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            // Unreadable manifest == no manifest. We re-fetch; we never guess.
            return;
        }
        for (String line : lines) {
            parse(line);
        }
    }

    /** {@code x,z=token,size}. Anything else is skipped in silence -- see the class doc. */
    private void parse(String line) {
        int equals = line.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String[] coords = line.substring(0, equals).split(",", -1);
        String[] values = line.substring(equals + 1).split(",", -1);
        if (coords.length != 2 || values.length != 2) {
            return;
        }
        try {
            this.entries.put(
                    key(Integer.parseInt(coords[0].trim()), Integer.parseInt(coords[1].trim())),
                    new Entry(Long.parseLong(values[0].trim()), Long.parseLong(values[1].trim())));
        } catch (NumberFormatException ignored) {
            // A line we cannot read is a region we cannot vouch for. Re-download it; do not crash over it.
        }
    }

    private static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }
}
