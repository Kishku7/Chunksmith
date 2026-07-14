package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodSummary;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The client's record of what the SERVER said about each region it holds -- and the compare that the
 * periodic sync is built on.
 *
 * <p>Since 3.1.0-beta-4 the freshness token is derived from the server's (mtime, size), which the client
 * cannot reproduce: the mtime of the client's copy is when the CLIENT wrote it. So the token is opaque, and
 * the client's job is to REMEMBER it rather than recompute it. That is this class, and this is its contract.
 */
public class CsLodManifestTest {

    private static final String DIM = "minecraft_overworld";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path root;

    private Path setUpStore() throws IOException {
        this.root = this.temp.newFolder("lod").toPath();
        Files.createDirectories(this.root.resolve(DIM));
        return this.root;
    }

    private void region(final int x, final int z, final int size) throws IOException {
        Files.write(this.root.resolve(DIM).resolve("r." + x + "." + z + ".cslod"), new byte[size]);
    }

    private static CsLodMessages.RegionEntry entry(final int x, final int z, final long hash, final long size) {
        return new CsLodMessages.RegionEntry(x, z, hash, size);
    }

    // ------------------------------------------------------------------ the record itself

    @Test
    public void whatWeWroteIsWhatWeReadBack() throws IOException {
        setUpStore();
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 0xDEAD_BEEF_1234_5678L, 4_812_345L);
        manifest.put(-3, 7, 42L, 17L);
        manifest.save();

        final CsLodManifest reopened = CsLodManifest.open(this.root, DIM);
        assertEquals(2, reopened.size());
        assertEquals(Long.valueOf(0xDEAD_BEEF_1234_5678L).longValue(), reopened.get(0, 0).hash());
        assertEquals(4_812_345L, reopened.get(0, 0).sizeBytes());
        assertEquals("negative coordinates round-trip", 42L, reopened.get(-3, 7).hash());
        assertNull(reopened.get(9, 9));
    }

    /** An UPGRADE from 3.1.0-beta-3: regions on disk, no manifest. Everything is re-fetched, once. */
    @Test
    public void aStoreWithNoManifestVouchesForNothing() throws IOException {
        setUpStore();
        region(0, 0, 100);

        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);

        assertEquals(0, manifest.size());
        assertFalse("the file is there, but we cannot say WHICH version it is",
                manifest.holds(this.root.resolve(DIM), entry(0, 0, 777L, 100L)));
    }

    /** A malformed manifest is a manifest we re-download from. It is never a crash. */
    @Test
    public void aCorruptManifestIsSurvivable() throws IOException {
        setUpStore();
        Files.write(this.root.resolve(DIM).resolve(".manifest"),
                ("0,0=111,100\n"
                 + "this is not a manifest line\n"
                 + "1,1=notanumber,100\n"
                 + "\n"
                 + "2,2=222,200\n").getBytes(StandardCharsets.US_ASCII));

        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);

        assertEquals("the good lines survive, the bad ones are dropped", 2, manifest.size());
        assertEquals(111L, manifest.get(0, 0).hash());
        assertEquals(222L, manifest.get(2, 2).hash());
        assertNull(manifest.get(1, 1));
    }

    // ------------------------------------------------------------------ holds()

    @Test
    public void weHoldARegionWhenTheTokenAndTheSizeBothMatch() throws IOException {
        setUpStore();
        region(0, 0, 100);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);

        assertTrue(manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));
    }

    /** The server grew the region: its token moved, so our copy is stale and must be re-fetched. */
    @Test
    public void aMovedTokenMeansWeDoNotHoldIt() throws IOException {
        setUpStore();
        region(0, 0, 100);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);

        assertFalse(manifest.holds(this.root.resolve(DIM), entry(0, 0, 1000L, 120L)));
    }

    /** The file was DELETED under us. The manifest still lists it -- the stat is what catches this. */
    @Test
    public void aDeletedRegionIsNotHeld() throws IOException {
        setUpStore();
        region(0, 0, 100);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);
        assertTrue(manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));

        Files.delete(this.root.resolve(DIM).resolve("r.0.0.cslod"));

        assertFalse("a manifest entry is not a region file", 
                manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));
    }

    /** The file was TRUNCATED. Same story. */
    @Test
    public void aTruncatedRegionIsNotHeld() throws IOException {
        setUpStore();
        region(0, 0, 100);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);

        region(0, 0, 50);

        assertFalse(manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));
    }

    /** A zero token is the server declining to describe the region. We never vouch for one. */
    @Test
    public void aZeroTokenIsNeverHeld() throws IOException {
        setUpStore();
        region(0, 0, 100);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 0L, 100L);

        assertFalse(manifest.holds(this.root.resolve(DIM), entry(0, 0, 0L, 100L)));
    }

    /** A traversal attempt in the dimension id is refused before it becomes a path. */
    @Test
    public void aMalformedDimensionIsRefused() throws IOException {
        setUpStore();
        assertNull(CsLodManifest.open(this.root, "../../etc"));
        assertNull(CsLodManifest.open(this.root, ".."));
        assertNull(CsLodManifest.open(this.root, ""));
    }

    // ------------------------------------------------------------------ fold() -- the sync compare

    /**
     * THE SYNC, in miniature. We hold everything the server last described -> our fold equals the server's,
     * and the poll is free.
     */
    @Test
    public void holdingEverythingFoldsToTheServersOwnAnswer() throws IOException {
        setUpStore();
        region(0, 0, 100);
        region(0, 1, 200);
        region(1, 0, 300);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);
        manifest.put(0, 1, 222L, 200L);
        manifest.put(1, 0, 333L, 300L);

        final List<CsLodMessages.RegionEntry> index = List.of(
                entry(0, 0, 111L, 100L), entry(0, 1, 222L, 200L), entry(1, 0, 333L, 300L));

        final CsLodSummary.Snapshot ours = manifest.fold(this.root.resolve(DIM), index);

        // What the server would compute over the same set.
        long server = 0L;
        for (final CsLodMessages.RegionEntry e : index) {
            server = CsLodSummary.fold(server, e.regionX(), e.regionZ(), e.hash());
        }

        assertEquals(3, ours.count());
        assertEquals("nothing changed -> no index, no fetch", server, ours.aggregate());
    }

    /** THE SERVER GREW. It advertises a region we have never seen -> we disagree, and pull an index. */
    @Test
    public void aRegionWeHaveNeverSeenMakesUsDisagree() throws IOException {
        setUpStore();
        region(0, 0, 100);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);

        final List<CsLodMessages.RegionEntry> index =
                List.of(entry(0, 0, 111L, 100L), entry(5, 5, 555L, 500L));

        final CsLodSummary.Snapshot ours = manifest.fold(this.root.resolve(DIM), index);

        assertEquals("we can only vouch for one of the two", 1, ours.count());
        assertNotEquals(2, ours.count());
    }

    /** THE CLIENT LOST REGIONS. Delete one from disk -> our count falls and the aggregate moves. */
    @Test
    public void deletingARegionFromOurOwnStoreMakesUsDisagree() throws IOException {
        setUpStore();
        region(0, 0, 100);
        region(0, 1, 200);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);
        manifest.put(0, 1, 222L, 200L);

        final List<CsLodMessages.RegionEntry> index =
                List.of(entry(0, 0, 111L, 100L), entry(0, 1, 222L, 200L));

        final CsLodSummary.Snapshot before = manifest.fold(this.root.resolve(DIM), index);
        assertEquals(2, before.count());

        Files.delete(this.root.resolve(DIM).resolve("r.0.1.cslod"));

        final CsLodSummary.Snapshot after = manifest.fold(this.root.resolve(DIM), index);
        assertEquals("the deleted region stops contributing", 1, after.count());
        assertNotEquals(before.aggregate(), after.aggregate());
    }

    /** A REGION CHANGED. The server's token moved -> ours stops contributing -> we disagree. */
    @Test
    public void aChangedRegionMakesUsDisagree() throws IOException {
        setUpStore();
        region(0, 0, 100);
        final CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);

        final CsLodSummary.Snapshot same =
                manifest.fold(this.root.resolve(DIM), List.of(entry(0, 0, 111L, 100L)));
        final CsLodSummary.Snapshot grown =
                manifest.fold(this.root.resolve(DIM), List.of(entry(0, 0, 999L, 140L)));

        assertEquals(1, same.count());
        assertEquals("we cannot vouch for the version the server now has", 0, grown.count());
        assertNotEquals(same.aggregate(), grown.aggregate());
    }
}
