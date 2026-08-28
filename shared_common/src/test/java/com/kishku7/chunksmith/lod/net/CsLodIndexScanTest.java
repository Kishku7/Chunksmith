package com.kishku7.chunksmith.lod.net;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for "which regions can this player see?" -- the answer both the loaders and the Bukkit plugin
 * now serve from {@link CsLodIndexScan}.
 *
 * <p>These exist because the scan is the one piece of the LOD path where a wrong answer is invisible.
 * Nothing throws, nothing logs; the client simply fetches the wrong set, or none, and the operator
 * sees a server that looks perfectly healthy.
 *
 * <p>The two rules that matter most, and are the easiest to break by accident:
 *
 * <ul>
 *   <li><b>Range is measured to the region's box, not its corner.</b> A region is 512 blocks square,
 *       so one whose near edge is inside the radius holds terrain the player can see even though its
 *       origin is far outside it. Testing the corner leaves visible holes at the edge of the draw
 *       distance.</li>
 *   <li><b>The index and the sync summary must be computed over the same set.</b> If they are not, an
 *       idle poll finds a difference, pulls a full index, discovers nothing to fetch, and does it
 *       again on the next interval -- forever, on every client.</li>
 * </ul>
 *
 * <p>The clock is a parameter, so "ten seconds later" costs nothing and the settle rule is testable
 * without sleeping.
 */
public class CsLodIndexScanTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** A moment comfortably past the settle window for anything written "now". */
    private static long settled() {
        return System.currentTimeMillis() + CsLodStoreScan.SETTLE_MILLIS * 10L;
    }

    private static CsLodIndexScan.Request at(final int px, final int pz, final int radius) {
        return new CsLodIndexScan.Request("minecraft_overworld", px, pz, radius);
    }

    private Path region(final int x, final int z, final int bytes) throws IOException {
        final Path file = temp.getRoot().toPath().resolve("r." + x + "." + z + ".cslod");
        Files.write(file, new byte[bytes]);
        return file;
    }

    // ---------------------------------------------------------------- nothing to serve

    @Test
    public void aMissingDirectoryScansToNothing() throws IOException {
        final CsLodIndexScan.Result result =
                CsLodIndexScan.scan(temp.getRoot().toPath().resolve("nope"), at(0, 0, 4096), settled());
        assertTrue(result.regions().isEmpty());
        assertEquals(0, result.found());
        assertFalse(result.capped());
    }

    @Test
    public void nullScansToNothing() throws IOException {
        assertTrue(CsLodIndexScan.scan(null, at(0, 0, 4096), settled()).regions().isEmpty());
    }

    @Test
    public void emptyStoreIsEmpty() throws IOException {
        assertTrue(CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 4096), settled())
                .regions().isEmpty());
    }

    // ---------------------------------------------------------------- what counts as ours

    @Test
    public void onlyRegionFiles() throws IOException {
        region(0, 0, 16);
        Files.write(temp.getRoot().toPath().resolve("notes.txt"), new byte[16]);
        Files.write(temp.getRoot().toPath().resolve("r.0.0.mca"), new byte[16]);
        Files.write(temp.getRoot().toPath().resolve("r.0.cslod"), new byte[16]);
        Files.write(temp.getRoot().toPath().resolve("r.x.0.cslod"), new byte[16]);

        final List<CsLodMessages.RegionEntry> regions =
                CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 4096), settled()).regions();
        assertEquals(1, regions.size());
        assertEquals(0, regions.get(0).regionX());
        assertEquals(0, regions.get(0).regionZ());
    }

    @Test
    public void negativeCoordinatesParse() throws IOException {
        region(-3, -4, 16);
        final List<CsLodMessages.RegionEntry> regions =
                CsLodIndexScan.scan(temp.getRoot().toPath(), at(-1536, -2048, 4096), settled()).regions();
        assertEquals(1, regions.size());
        assertEquals(-3, regions.get(0).regionX());
        assertEquals(-4, regions.get(0).regionZ());
    }

    // ---------------------------------------------------------------- the settle rule

    @Test
    public void anUnsettledRegionIsNotServed() throws IOException {
        region(0, 0, 16);
        // "now" -- the file was written this instant, so it is inside the settle window.
        assertTrue(CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 4096), System.currentTimeMillis())
                .regions().isEmpty());
    }

    @Test
    public void settledIsServed() throws IOException {
        region(0, 0, 16);
        assertEquals(1, CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 4096), settled())
                .regions().size());
    }

    // ---------------------------------------------------------------- range is a box test

    @Test
    public void rangeIsMeasuredToTheNearEdge() {
        // Region 2 spans blocks 1024..1535. A player at the origin is 1024 blocks from its near edge,
        // not 1024+511 from its far one, and not 0 from its "position".
        assertEquals(1024L * 1024L, CsLodIndexScan.distanceSquared(at(0, 0, 1), 2, 0));
        assertFalse(CsLodIndexScan.inRange(at(0, 0, 1023), 2, 0));
        assertTrue(CsLodIndexScan.inRange(at(0, 0, 1024), 2, 0));
    }

    @Test
    public void insideIsZeroBlocks() {
        assertEquals(0L, CsLodIndexScan.distanceSquared(at(300, 300, 1), 0, 0));
        assertTrue(CsLodIndexScan.inRange(at(300, 300, 0), 0, 0));
    }

    @Test
    public void regionsOutOfRangeAreNotSent() throws IOException {
        region(0, 0, 16);
        region(8, 8, 16);   // near edge at 4096,4096

        assertEquals(1, CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 512), settled())
                .regions().size());
        assertEquals(2, CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 8192), settled())
                .regions().size());
    }

    // ---------------------------------------------------------------- ordering

    @Test
    public void nearestFirst() throws IOException {
        region(4, 0, 16);
        region(1, 0, 16);
        region(2, 0, 16);
        region(0, 0, 16);

        final List<CsLodMessages.RegionEntry> regions =
                CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 8192), settled()).regions();
        assertEquals(4, regions.size());
        assertEquals(0, regions.get(0).regionX());
        assertEquals(1, regions.get(1).regionX());
        assertEquals(2, regions.get(2).regionX());
        assertEquals(4, regions.get(3).regionX());
    }

    @Test
    public void orderingIsStableForRegionsTheSameDistanceAway() throws IOException {
        region(1, 0, 16);
        region(-1, 0, 16);
        region(0, 1, 16);
        region(0, -1, 16);

        final List<CsLodMessages.RegionEntry> first =
                CsLodIndexScan.scan(temp.getRoot().toPath(), at(255, 255, 8192), settled()).regions();
        final List<CsLodMessages.RegionEntry> again =
                CsLodIndexScan.scan(temp.getRoot().toPath(), at(255, 255, 8192), settled()).regions();
        assertEquals(first, again);
    }

    // ---------------------------------------------------------------- the caps

    @Test
    public void theCountCapTruncatesAndSaysSo() throws IOException {
        // Zero-byte regions, so only the count cap can bind. One more than the ceiling.
        for (int x = 0; x <= CsLodIndexScan.MAX_REGIONS; x++) {
            region(x, 0, 0);
        }
        final CsLodIndexScan.Result result =
                CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, Integer.MAX_VALUE / 2), settled());
        assertEquals(CsLodIndexScan.MAX_REGIONS, result.regions().size());
        assertEquals(CsLodIndexScan.MAX_REGIONS + 1, result.found());
        assertTrue(result.capped());
        // The one it dropped is the furthest, which is the one the player can least see.
        assertEquals(CsLodIndexScan.MAX_REGIONS - 1,
                result.regions().get(result.regions().size() - 1).regionX());
    }

    @Test
    public void anUncappedScanIsNotCapped() throws IOException {
        region(0, 0, 16);
        final CsLodIndexScan.Result result =
                CsLodIndexScan.scan(temp.getRoot().toPath(), at(0, 0, 8192), settled());
        assertFalse(result.capped());
        assertEquals(1, result.found());
        assertEquals(16L, result.bytes());
    }

    // ---------------------------------------------------------------- index and summary agree

    @Test
    public void theSummaryIsTheIndexFolded() throws IOException {
        region(0, 0, 16);
        region(1, 0, 32);
        region(9, 9, 64);   // outside the radius below, so it must be in NEITHER answer

        final CsLodIndexScan.Request request = at(0, 0, 1024);
        final List<CsLodMessages.RegionEntry> regions =
                CsLodIndexScan.scan(temp.getRoot().toPath(), request, settled()).regions();
        assertEquals(2, regions.size());

        long expected = 0L;
        for (final CsLodMessages.RegionEntry entry : regions) {
            expected = CsLodSummary.fold(expected, entry.regionX(), entry.regionZ(), entry.hash());
        }
        assertEquals(expected, CsLodIndexScan.aggregate(regions));
    }

    @Test
    public void anEmptySetFoldsToZero() {
        assertEquals(0L, CsLodIndexScan.aggregate(List.of()));
    }
}
