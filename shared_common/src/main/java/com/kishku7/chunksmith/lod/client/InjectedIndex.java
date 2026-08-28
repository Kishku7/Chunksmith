package com.kishku7.chunksmith.lod.client;

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
 * Which regions we have already handed to a renderer, REMEMBERED ACROSS SESSIONS.
 *
 * <p><b>Why it exists.</b> {@link InjectedRegions} answers "have I drawn this version of this region?" for
 * one session and is thrown away on disconnect, so a JOIN starts from nothing: every region in range is
 * claimed, decoded and pushed again into a renderer that already persisted it -- voxy keeps its own
 * database, Distant Horizons its own sqlite. That was minutes of CPU on every world join, on a large
 * pregenerated world on a two-core machine (mod_support #15). This is the on-disk half: a sidecar per
 * dimension recording the token of the version of each region we last injected, which a join seeds
 * {@link InjectedRegions} from.
 *
 * <p><b>The token, not the coordinates.</b> A pregen does not only create NEW regions, it keeps GROWING the
 * ones the player is standing on. A region whose token has moved must be re-injected or the terrain under
 * the player's feet freezes at whatever it was the first time they joined.
 *
 * <p><b>The epoch.</b> The first line is {@code #epoch=<renderers>|<store version>}; a mismatch discards the
 * whole file and everything is injected again. That covers the otherwise silent and permanent case of a
 * player who had voxy, injected everything, then installed Distant Horizons. What it CANNOT see is the
 * player emptying the renderer's own database -- nothing we can read says voxy's storage was reset -- so
 * there is {@code reinject-on-join} in {@code config/chunksmith-lod.properties}, and deleting these files by
 * hand does the same.
 *
 * <p><b>Format.</b> One line per region, {@code x,z=token}, plain ASCII, written atomically via a
 * {@code .part} file and a move. Malformed lines are SKIPPED. Every failure mode here ends at "inject it
 * again": re-injecting costs CPU, but SKIPPING costs the player terrain.
 *
 * <p>Thread-safe. The injector writes it off the game thread while the network handler may be reading.
 */
public final class InjectedIndex {

    /** The sidecar's name, inside the dimension directory, next to the regions it describes. */
    static final String FILE_NAME = ".injected";

    /** First line of the file. Not a comment to be skipped -- an assertion about who this record is for. */
    private static final String EPOCH_PREFIX = "#epoch=";

    private final Path file;
    private final String epoch;
    private final Map<Long, Long> entries = new ConcurrentHashMap<>();

    private InjectedIndex(final Path file, final String epoch) {
        this.file = file;
        this.epoch = epoch;
    }

    /**
     * Open (or start) the injected-index for one dimension of one store. A malformed dimension id returns
     * null and the caller must refuse the whole operation.
     *
     * @param epoch  identifies the renderer set this record was made for; a mismatch discards the file
     * @param ignore true to start empty regardless of what is on disk ({@code reinject-on-join})
     */
    public static InjectedIndex open(final Path storeRoot, final String dimension, final String epoch,
                                     final boolean ignore) {
        final Path dir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dir == null) {
            return null;
        }
        final InjectedIndex index = new InjectedIndex(dir.resolve(FILE_NAME), epoch);
        if (!ignore) {
            index.load();
        }
        return index;
    }

    /**
     * The epoch string for a renderer set. Built from the renderers present, because that is the one change
     * that would otherwise make us skip -- forever and silently -- data a newly-installed renderer has never
     * been given.
     */
    public static String epochFor(final boolean voxy, final boolean dh, final int storeVersion) {
        return (voxy ? "voxy" : "-") + "+" + (dh ? "dh" : "-") + "|v" + storeVersion;
    }

    /** Record that this version of this region has been injected. Call AFTER the region is really in. */
    public void put(final int regionX, final int regionZ, final long token) {
        this.entries.put(key(regionX, regionZ), token);
    }

    /** Forget a region -- it was released, or it failed half way. The next join re-injects it. */
    public void remove(final int regionX, final int regionZ) {
        this.entries.remove(key(regionX, regionZ));
    }

    /** How many regions this record claims. */
    public int size() {
        return this.entries.size();
    }

    /** Every remembered region, as {@code {x, z, token}} triples. Used to seed the session's claim set. */
    public List<long[]> entries() {
        final List<long[]> out = new ArrayList<>(this.entries.size());
        for (final Map.Entry<Long, Long> entry : this.entries.entrySet()) {
            final long packed = entry.getKey();
            out.add(new long[] { (int) (packed >> 32), (int) packed, entry.getValue() });
        }
        return out;
    }

    /**
     * Write the record out, atomically -- see the class doc. Failure is survivable and only logged: we
     * re-inject next session, which is the behaviour this class replaces -- slow, not wrong.
     */
    public void save() throws IOException {
        final List<String> lines = new ArrayList<>(this.entries.size() + 1);
        lines.add(EPOCH_PREFIX + this.epoch);
        for (final Map.Entry<Long, Long> entry : this.entries.entrySet()) {
            final long packed = entry.getKey();
            lines.add((int) (packed >> 32) + "," + (int) packed + "=" + entry.getValue());
        }
        Files.createDirectories(this.file.getParent());
        final Path temp = this.file.resolveSibling(FILE_NAME + ".part");
        Files.write(temp, lines, StandardCharsets.US_ASCII);
        Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Read whatever is there. A missing file means only that we cannot vouch for anything and will inject it
     * all once.
     *
     * <p>A file whose epoch does not match ours is discarded WHOLE: keeping the half that might still apply
     * would mean deciding which renderer each line was for, and the file does not record that.
     */
    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        final List<String> lines;
        try {
            lines = Files.readAllLines(this.file, StandardCharsets.US_ASCII);
        } catch (final IOException e) {
            // Unreadable record == no record. We re-inject; we never guess in the direction of skipping.
            return;
        }
        if (lines.isEmpty() || !lines.get(0).equals(EPOCH_PREFIX + this.epoch)) {
            return;
        }
        for (int i = 1; i < lines.size(); i++) {
            parse(lines.get(i));
        }
    }

    /** {@code x,z=token}. Anything else is skipped in silence -- see the class doc. */
    private void parse(final String line) {
        final int equals = line.indexOf('=');
        if (equals <= 0) {
            return;
        }
        final String[] coords = line.substring(0, equals).split(",", -1);
        if (coords.length != 2) {
            return;
        }
        try {
            this.entries.put(
                    key(Integer.parseInt(coords[0].trim()), Integer.parseInt(coords[1].trim())),
                    Long.parseLong(line.substring(equals + 1).trim()));
        } catch (final NumberFormatException ignored) {
            // A line we cannot read is a region we cannot vouch for. Re-inject it; do not crash over it.
        }
    }

    private static long key(final int regionX, final int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }
}
