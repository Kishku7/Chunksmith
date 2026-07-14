package com.kishku7.chunksmith.lod.client;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The sync interval, its default, and THE CLAMP.
 *
 * <p>The floor is enforced in code, not in the file, and that is the point of these tests. A config value is
 * a suggestion from whoever last edited the file; {@code sync-interval-seconds=1} must not be able to turn
 * the self-healing sync into a poll storm against a server that is trying to run a pregen -- which is the
 * exact class of problem this release exists to fix.
 */
public class CsLodClientConfigTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void theDefaultIsFiveMinutes() {
        assertEquals(300, CsLodClientConfig.DEFAULT_SYNC_SECONDS);
    }

    @Test
    public void theFloorIsThirtySeconds() {
        assertEquals(30, CsLodClientConfig.MIN_SYNC_SECONDS);
    }

    /** THE CLAMP. Anything under thirty seconds becomes thirty seconds. */
    @Test
    public void anythingBelowThirtySecondsIsClampedToThirty() {
        assertEquals(30, CsLodClientConfig.clamp(29));
        assertEquals(30, CsLodClientConfig.clamp(10));
        assertEquals(30, CsLodClientConfig.clamp(1));
        assertEquals(30, CsLodClientConfig.clamp(0));
        assertEquals("a negative interval is not a fast one", 30, CsLodClientConfig.clamp(-600));
        assertEquals(30, CsLodClientConfig.clamp(Integer.MIN_VALUE));
    }

    /** ...and the clamp is a FLOOR, not a rewrite. Legal values pass through untouched. */
    @Test
    public void legalValuesAreNotTouched() {
        assertEquals(30, CsLodClientConfig.clamp(30));
        assertEquals(31, CsLodClientConfig.clamp(31));
        assertEquals(300, CsLodClientConfig.clamp(300));
        assertEquals(86_400, CsLodClientConfig.clamp(86_400));
        assertEquals("there is no ceiling -- a long interval only hurts the person who set it",
                Integer.MAX_VALUE, CsLodClientConfig.clamp(Integer.MAX_VALUE));
    }

    /** A missing config file is written with the defaults, and the defaults are what we then use. */
    @Test
    public void aMissingConfigIsWrittenWithTheDefaults() throws IOException {
        final Path dir = temp.newFolder("config").toPath();

        final String said = CsLodClientConfig.load(dir);

        assertEquals(300, CsLodClientConfig.syncIntervalSeconds());
        assertEquals(300_000L, CsLodClientConfig.syncIntervalMillis());
        assertTrue(CsLodClientConfig.isLoaded());
        assertTrue(said, said.contains("wrote"));
        assertTrue("the file is there for a player to find",
                Files.isRegularFile(dir.resolve(CsLodClientConfig.FILE_NAME)));
    }

    /** A CONFIGURED VALUE BELOW THE FLOOR IS CLAMPED -- read from a real file, through the real loader. */
    @Test
    public void aConfiguredValueBelowTheFloorIsClamped() throws IOException {
        final Path dir = write("sync-interval-seconds=5");

        final String said = CsLodClientConfig.load(dir);

        assertEquals("5 seconds is not honoured", 30, CsLodClientConfig.syncIntervalSeconds());
        assertEquals(30_000L, CsLodClientConfig.syncIntervalMillis());
        assertTrue("and the player is TOLD why: " + said, said.contains("minimum"));
    }

    @Test
    public void aConfiguredValueAboveTheFloorIsHonoured() throws IOException {
        final Path dir = write("sync-interval-seconds=45");

        CsLodClientConfig.load(dir);

        assertEquals(45, CsLodClientConfig.syncIntervalSeconds());
        assertEquals(45_000L, CsLodClientConfig.syncIntervalMillis());
    }

    /** Garbage in the file must never be the reason a player gets no terrain. */
    @Test
    public void anUnparseableValueFallsBackToTheDefault() throws IOException {
        final Path dir = write("sync-interval-seconds=soon");

        final String said = CsLodClientConfig.load(dir);

        assertEquals(300, CsLodClientConfig.syncIntervalSeconds());
        assertTrue(said, said.contains("not a number"));
    }

    @Test
    public void aMissingKeyFallsBackToTheDefault() throws IOException {
        final Path dir = write("something-else=1");

        CsLodClientConfig.load(dir);

        assertEquals(300, CsLodClientConfig.syncIntervalSeconds());
    }

    /** There is no way to obtain an unclamped interval, whatever the file said. */
    @Test
    public void theAccessorsCanNeverReturnLessThanTheFloor() throws IOException {
        for (final String value : new String[]{"0", "1", "-1", "-2147483648", "29"}) {
            CsLodClientConfig.load(write("sync-interval-seconds=" + value));
            assertTrue("interval=" + value,
                    CsLodClientConfig.syncIntervalSeconds() >= CsLodClientConfig.MIN_SYNC_SECONDS);
            assertTrue("interval=" + value,
                    CsLodClientConfig.syncIntervalMillis() >= CsLodClientConfig.MIN_SYNC_SECONDS * 1000L);
        }
    }

    private Path write(final String line) throws IOException {
        final Path dir = temp.newFolder().toPath();
        Files.write(dir.resolve(CsLodClientConfig.FILE_NAME),
                line.getBytes(StandardCharsets.US_ASCII));
        return dir;
    }
}
