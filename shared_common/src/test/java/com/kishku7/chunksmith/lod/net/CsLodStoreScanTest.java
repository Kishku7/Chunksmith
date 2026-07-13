package com.kishku7.chunksmith.lod.net;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for "is there anything here to serve?" -- the store-availability transition.
 *
 * <p>Worth pinning down because every wrong answer here is silent, and two of them have already shipped.
 *
 * <ul>
 *   <li>A false "yes, the directory is there" is what the old code gave: a pregen creates the dimension
 *       directory the moment it starts, the server advertised it before a single region was written, minted
 *       a backchannel token for it, and the client downloaded nothing -- the "1 live token, 0 files" report.</li>
 *   <li>A false "yes, that region is ready" is the one this fix nearly introduced: the store keeps region
 *       files OPEN and appends to them, so a region served mid-write has header slots pointing past the end
 *       of the bytes the client receives. It recovers, but it eats an EOF and gets a fraction of the region.</li>
 *   <li>And a false "no" is the whole bug: the client is told there is no data, stands down, and never asks
 *       again for the rest of the session.</li>
 * </ul>
 *
 * <p>So: a dimension is servable when it CONTAINS a region the writer has FINISHED with.
 *
 * <p>The clock is passed in, so "ten seconds later" costs nothing to test.
 */
public class CsLodStoreScanTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** A moment comfortably past the settle window for anything written "now". */
    private static long settled() {
        return System.currentTimeMillis() + CsLodStoreScan.SETTLE_MILLIS * 10L;
    }

    @Test
    public void aDirectoryThatDoesNotExistIsNotServable() {
        assertFalse(CsLodStoreScan.hasData(temp.getRoot().toPath().resolve("nope"), settled()));
    }

    @Test
    public void nullIsNotServable() {
        assertFalse(CsLodStoreScan.hasData(null, settled()));
    }

    @Test
    public void aFileIsNotADimensionDirectory() throws IOException {
        assertFalse(CsLodStoreScan.hasData(temp.newFile("minecraft_overworld").toPath(), settled()));
    }

    /** The original bug, in one assertion: the pregen has made the folder but not yet written into it. */
    @Test
    public void anEmptyDimensionDirectoryIsNotServable() throws IOException {
        final Path dir = temp.newFolder("minecraft_overworld").toPath();
        assertTrue(Files.isDirectory(dir));
        assertFalse("an empty store directory must never be advertised as data",
                CsLodStoreScan.hasData(dir, settled()));
    }

    /** A half-written region is not a region. The client writes .part and moves it into place when whole. */
    @Test
    public void aPartialRegionIsNotServable() throws IOException {
        final Path dir = temp.newFolder("minecraft_overworld").toPath();
        Files.write(dir.resolve("r.0.0.cslod.part"), new byte[]{1, 2, 3});
        Files.write(dir.resolve("readme.txt"), new byte[]{1});
        assertFalse(CsLodStoreScan.hasData(dir, settled()));
    }

    /**
     * The region the pregen is writing RIGHT NOW is not servable, even though it exists and has bytes in it.
     * This is the EOF the notification would otherwise hand every player.
     */
    @Test
    public void aRegionTheWriterIsStillTouchingIsNotServable() throws IOException {
        final Path dir = temp.newFolder("minecraft_overworld").toPath();
        final Path region = dir.resolve("r.0.0.cslod");
        Files.write(region, new byte[]{1, 2, 3});

        final long justWritten = Files.getLastModifiedTime(region).toMillis();

        assertFalse("a region touched a moment ago is still being appended to",
                CsLodStoreScan.isSettled(region, justWritten));
        assertFalse(CsLodStoreScan.isSettled(region, justWritten + CsLodStoreScan.SETTLE_MILLIS - 1));
        assertFalse(CsLodStoreScan.hasData(dir, justWritten));

        // ...and the moment the writer has left it alone long enough, it is.
        assertTrue(CsLodStoreScan.isSettled(region, justWritten + CsLodStoreScan.SETTLE_MILLIS));
        assertTrue(CsLodStoreScan.hasData(dir, justWritten + CsLodStoreScan.SETTLE_MILLIS));
    }

    /** And the transition itself: the same directory, one finished region later. */
    @Test
    public void oneFinishedRegionFileMakesItServable() throws IOException {
        final Path dir = temp.newFolder("minecraft_overworld").toPath();
        assertFalse(CsLodStoreScan.hasData(dir, settled()));

        Files.write(dir.resolve("r.0.0.cslod"), new byte[]{1, 2, 3});

        assertTrue("once the first region is written and settled, the store is servable",
                CsLodStoreScan.hasData(dir, settled()));
    }

    @Test
    public void servableNamesOnlyTheDimensionsThatHaveFinishedData() throws IOException {
        final Path overworld = temp.newFolder("minecraft_overworld").toPath();
        final Path nether = temp.newFolder("minecraft_the_nether").toPath();
        final Path end = temp.newFolder("minecraft_the_end").toPath();

        Files.write(overworld.resolve("r.0.0.cslod"), new byte[]{1});
        Files.write(end.resolve("r.-1.4.cslod"), new byte[]{1});
        // nether: directory made by the pregen, nothing written yet.

        assertEquals(List.of("minecraft_overworld", "minecraft_the_end"),
                CsLodStoreScan.servable(List.of(overworld, nether, end), settled()));
    }

    /** A store whose only region is still being written reads as "nothing to serve yet" -- correctly. */
    @Test
    public void servableSkipsADimensionWhoseOnlyRegionIsStillBeingWritten() throws IOException {
        final Path overworld = temp.newFolder("minecraft_overworld").toPath();
        final Path region = overworld.resolve("r.0.0.cslod");
        Files.write(region, new byte[]{1});
        final long justWritten = Files.getLastModifiedTime(region).toMillis();

        assertTrue(CsLodStoreScan.servable(List.of(overworld), justWritten).isEmpty());
        assertEquals(List.of("minecraft_overworld"),
                CsLodStoreScan.servable(List.of(overworld), justWritten + CsLodStoreScan.SETTLE_MILLIS));
    }

    /** A region that is re-written (a re-run filling holes) goes hot again, then settles again. */
    @Test
    public void aRewrittenRegionGoesHotAgainAndThenSettles() throws IOException {
        final Path dir = temp.newFolder("minecraft_overworld").toPath();
        final Path region = dir.resolve("r.0.0.cslod");
        Files.write(region, new byte[]{1});

        final long oldWrite = System.currentTimeMillis() - CsLodStoreScan.SETTLE_MILLIS * 5L;
        Files.setLastModifiedTime(region, FileTime.fromMillis(oldWrite));
        assertTrue(CsLodStoreScan.hasData(dir, System.currentTimeMillis()));

        // The pregen touches it again.
        final long now = System.currentTimeMillis();
        Files.setLastModifiedTime(region, FileTime.fromMillis(now));
        assertFalse(CsLodStoreScan.hasData(dir, now));
        assertTrue(CsLodStoreScan.hasData(dir, now + CsLodStoreScan.SETTLE_MILLIS));
    }

    @Test
    public void servableIsEmptyWhenNothingHasBeenPregeneratedYet() throws IOException {
        final Path overworld = temp.newFolder("minecraft_overworld").toPath();
        assertTrue(CsLodStoreScan.servable(List.of(overworld), settled()).isEmpty());
        assertTrue(CsLodStoreScan.servable(List.of(), settled()).isEmpty());
        assertTrue(CsLodStoreScan.servable(null, settled()).isEmpty());
    }
}
