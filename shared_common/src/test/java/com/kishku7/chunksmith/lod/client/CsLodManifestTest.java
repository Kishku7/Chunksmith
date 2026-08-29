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
 * The client's record of what the server said about each region it
 * holds -- and the compare that the periodic sync is built on.
 *
 * <p>Since 3.1.0-beta-4 the freshness token is derived from the
 * server's (mtime, size), which the client cannot reproduce: the
 * mtime of the client's copy is when the client wrote it. So the
 * token is opaque, and the client's job is to remember it rather
 * than recompute it. That is this class, and this is its contract.
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

    private void region(int x, int z, int size) throws IOException {
        Files.write(this.root.resolve(DIM).resolve("r." + x + "." + z + ".cslod"), new byte[size]);
    }

    private static CsLodMessages.RegionEntry entry(int x, int z, long hash, long size) {
        return new CsLodMessages.RegionEntry(x, z, hash, size);
    }

    // ------------------------------------------------------------------ the record itself

    @Test
    public void roundTrips() throws IOException {
        setUpStore();
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 0xDEAD_BEEF_1234_5678L, 4_812_345L);
        manifest.put(-3, 7, 42L, 17L);
        manifest.save();

        CsLodManifest reopened = CsLodManifest.open(this.root, DIM);
        assertEquals(2, reopened.size());
        assertEquals(Long.valueOf(0xDEAD_BEEF_1234_5678L).longValue(), reopened.get(0, 0).hash());
        assertEquals(4_812_345L, reopened.get(0, 0).sizeBytes());
        assertEquals("negative coordinates round-trip", 42L, reopened.get(-3, 7).hash());
        assertNull(reopened.get(9, 9));
    }

    /** An UPGRADE from 3.1.0-beta-3: regions on disk, no manifest. Everything is re-fetched, once. */
    @Test
    public void noManifestNoClaims() throws IOException {
        setUpStore();
        region(0, 0, 100);

        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);

        assertEquals(0, manifest.size());
        assertFalse("no manifest, no claim",
                manifest.holds(this.root.resolve(DIM), entry(0, 0, 777L, 100L)));
    }

    @Test
    public void aCorruptManifestIsSurvivable() throws IOException {
        setUpStore();
        Files.write(this.root.resolve(DIM).resolve(".manifest"),
                ("0,0=111,100\n"
                 + "this is not a manifest line\n"
                 + "1,1=notanumber,100\n"
                 + "\n"
                 + "2,2=222,200\n").getBytes(StandardCharsets.US_ASCII));

        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);

        assertEquals("good lines survive", 2, manifest.size());
        assertEquals(111L, manifest.get(0, 0).hash());
        assertEquals(222L, manifest.get(2, 2).hash());
        assertNull(manifest.get(1, 1));
    }

    // ------------------------------------------------------------------ holds()

    @Test
    public void holdsWhenTokenAndSizeMatch() throws IOException {
        setUpStore();
        region(0, 0, 100);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);

        assertTrue(manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));
    }

    @Test
    public void aMovedTokenIsNotHeld() throws IOException {
        setUpStore();
        region(0, 0, 100);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);

        assertFalse(manifest.holds(this.root.resolve(DIM), entry(0, 0, 1000L, 120L)));
    }

    @Test
    public void aDeletedRegionIsNotHeld() throws IOException {
        setUpStore();
        region(0, 0, 100);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);
        assertTrue(manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));

        Files.delete(this.root.resolve(DIM).resolve("r.0.0.cslod"));

        assertFalse("deleted region still held",
                manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));
    }

    @Test
    public void aTruncatedRegionIsNotHeld() throws IOException {
        setUpStore();
        region(0, 0, 100);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 999L, 100L);

        region(0, 0, 50);

        assertFalse(manifest.holds(this.root.resolve(DIM), entry(0, 0, 999L, 100L)));
    }

    @Test
    public void aZeroTokenIsNeverHeld() throws IOException {
        setUpStore();
        region(0, 0, 100);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 0L, 100L);

        assertFalse(manifest.holds(this.root.resolve(DIM), entry(0, 0, 0L, 100L)));
    }

    @Test
    public void aMalformedDimensionIsRefused() throws IOException {
        setUpStore();
        assertNull(CsLodManifest.open(this.root, "../../etc"));
        assertNull(CsLodManifest.open(this.root, ".."));
        assertNull(CsLodManifest.open(this.root, ""));
    }

    // ------------------------------------------------------------------ the fold() sync compare

    @Test
    public void holdingEverythingMatchesTheServer() throws IOException {
        setUpStore();
        region(0, 0, 100);
        region(0, 1, 200);
        region(1, 0, 300);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);
        manifest.put(0, 1, 222L, 200L);
        manifest.put(1, 0, 333L, 300L);

        List<CsLodMessages.RegionEntry> index = List.of(
                entry(0, 0, 111L, 100L), entry(0, 1, 222L, 200L), entry(1, 0, 333L, 300L));

        CsLodSummary.Snapshot ours = manifest.fold(this.root.resolve(DIM), index);

        // What the server would compute over the same set.
        long server = 0L;
        for (CsLodMessages.RegionEntry e : index) {
            server = CsLodSummary.fold(server, e.regionX(), e.regionZ(), e.hash());
        }

        assertEquals(3, ours.count());
        assertEquals("same set, same aggregate", server, ours.aggregate());
    }

    @Test
    public void anUnseenRegionMakesUsDisagree() throws IOException {
        setUpStore();
        region(0, 0, 100);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);

        List<CsLodMessages.RegionEntry> index =
                List.of(entry(0, 0, 111L, 100L), entry(5, 5, 555L, 500L));

        CsLodSummary.Snapshot ours = manifest.fold(this.root.resolve(DIM), index);

        assertEquals("one of the two", 1, ours.count());
        assertNotEquals(2, ours.count());
    }

    @Test
    public void deletingOurCopyMakesUsDisagree() throws IOException {
        setUpStore();
        region(0, 0, 100);
        region(0, 1, 200);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);
        manifest.put(0, 1, 222L, 200L);

        List<CsLodMessages.RegionEntry> index =
                List.of(entry(0, 0, 111L, 100L), entry(0, 1, 222L, 200L));

        CsLodSummary.Snapshot before = manifest.fold(this.root.resolve(DIM), index);
        assertEquals(2, before.count());

        Files.delete(this.root.resolve(DIM).resolve("r.0.1.cslod"));

        CsLodSummary.Snapshot after = manifest.fold(this.root.resolve(DIM), index);
        assertEquals("the deleted region stops contributing", 1, after.count());
        assertNotEquals(before.aggregate(), after.aggregate());
    }

    @Test
    public void aChangedRegionMakesUsDisagree() throws IOException {
        setUpStore();
        region(0, 0, 100);
        CsLodManifest manifest = CsLodManifest.open(this.root, DIM);
        manifest.put(0, 0, 111L, 100L);

        CsLodSummary.Snapshot same =
                manifest.fold(this.root.resolve(DIM), List.of(entry(0, 0, 111L, 100L)));
        CsLodSummary.Snapshot grown =
                manifest.fold(this.root.resolve(DIM), List.of(entry(0, 0, 999L, 140L)));

        assertEquals(1, same.count());
        assertEquals("the server's version is newer", 0, grown.count());
        assertNotEquals(same.aggregate(), grown.aggregate());
    }
}
