package com.kishku7.chunksmith.lod.client;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The on-disk injected record -- and the bug it exists to fix (mod_support #15).
 *
 * <p>Which regions had been handed to voxy / Distant Horizons was remembered in memory only, and cleared on
 * disconnect. Every world join therefore started from nothing and re-decoded and re-pushed the whole
 * in-range store into renderers that had persisted every bit of it since the last session. The reporter's
 * two-core machine made it obvious; on a fast machine it was merely invisible waste.
 *
 * <p>The tests below pin BOTH directions, because the dangerous failure is not the slow one. Skipping a
 * region the renderer does NOT have leaves a permanent hole in the horizon and reports success while it
 * does it -- so every ambiguity here has to resolve towards injecting again.
 */
public class InjectedIndexTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static final String OVERWORLD = "minecraft_overworld";
    private static final String NETHER = "minecraft_the_nether";

    /** Freshness tokens. Their VALUES are meaningless -- only sameness and difference matter. */
    private static final long V1 = 0x1111_2222_3333_4444L;
    private static final long V2 = 0x5555_6666_7777_8888L;

    private static final String EPOCH_VOXY = InjectedIndex.epochFor(true, false, 1);
    private static final String EPOCH_VOXY_DH = InjectedIndex.epochFor(true, true, 1);

    private Path root() {
        return temp.getRoot().toPath();
    }

    /** THE BUG. What one session injected, the next session must already know about. */
    @Test
    public void whatWasInjectedLastSessionIsRememberedByTheNext() throws IOException {
        final InjectedIndex first = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        first.put(0, 0, V1);
        first.put(-3, 7, V1);
        first.save();

        final InjectedIndex second = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        assertEquals(2, second.size());

        // And seeding the session set from it means those regions are NOT claimed again -- which is the
        // entire point: no re-decode, no re-push, no CPU spent redrawing what is already on screen.
        final InjectedRegions session = new InjectedRegions();
        for (final long[] entry : second.entries()) {
            session.seed(OVERWORLD, (int) entry[0], (int) entry[1], entry[2]);
        }
        assertFalse(session.claim(OVERWORLD, 0, 0, V1));
        assertFalse(session.claim(OVERWORLD, -3, 7, V1));
    }

    /**
     * A region that has CHANGED is still injected again.
     *
     * <p>Persisting the claim must not resurrect the bug {@link InjectedRegions} was given tokens to
     * prevent: a pregen keeps GROWING the region under the player, and freezing that terrain at whatever it
     * was on their first ever join is stranger than never drawing it at all.
     */
    @Test
    public void aRegionWhoseTokenMovedIsStillInjectedAgain() throws IOException {
        final InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(2, 2, V1);
        index.save();

        final InjectedRegions session = new InjectedRegions();
        for (final long[] entry : InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).entries()) {
            session.seed(OVERWORLD, (int) entry[0], (int) entry[1], entry[2]);
        }
        assertTrue("a grown region must be re-injected", session.claim(OVERWORLD, 2, 2, V2));
    }

    /**
     * Installing a renderer that has never seen any of it discards the whole record.
     *
     * <p>Without this, a player who ran voxy for a month and then added Distant Horizons would be told, by
     * our own file, that DH already had everything -- and DH would stay empty forever with nothing in any
     * log to say why.
     */
    @Test
    public void addingARendererDiscardsTheRecord() throws IOException {
        final InjectedIndex voxyOnly = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        voxyOnly.put(0, 0, V1);
        voxyOnly.put(1, 1, V1);
        voxyOnly.save();

        assertEquals("a different renderer set must not inherit the claims",
                0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY_DH, false).size());
        assertEquals("the original set still reads its own record",
                2, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    /** The escape hatch: reinject-on-join starts empty no matter what is on disk. */
    @Test
    public void reinjectOnJoinIgnoresTheRecord() throws IOException {
        final InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(4, 4, V1);
        index.save();

        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, true).size());
    }

    /** Region (0,0) is a different place in every dimension, and the sidecars are per dimension. */
    @Test
    public void dimensionsDoNotShareARecord() throws IOException {
        final InjectedIndex overworld = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        overworld.put(0, 0, V1);
        overworld.save();

        assertEquals(0, InjectedIndex.open(root(), NETHER, EPOCH_VOXY, false).size());
    }

    /** A record we cannot read is a record we do not have -- inject again, never crash, never guess. */
    @Test
    public void malformedLinesAreSkippedAndTheRestSurvives() throws IOException {
        final Path dir = root().resolve(OVERWORLD);
        Files.createDirectories(dir);
        final List<String> lines = Arrays.asList(
                "#epoch=" + EPOCH_VOXY,
                "0,0=" + V1,
                "not a line at all",
                "1=missing-a-coordinate",
                "2,2=not-a-number",
                "3,3=" + V2);
        Files.write(dir.resolve(".injected"), lines, StandardCharsets.US_ASCII);

        final InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        assertEquals("only the two readable lines count", 2, index.size());
    }

    /** A file with no epoch line at all predates the mechanism: draw everything once. */
    @Test
    public void aRecordWithNoEpochIsDiscarded() throws IOException {
        final Path dir = root().resolve(OVERWORLD);
        Files.createDirectories(dir);
        Files.write(dir.resolve(".injected"), Arrays.asList("0,0=" + V1), StandardCharsets.US_ASCII);

        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    /** No file yet is the normal first-run case, not an error. */
    @Test
    public void aMissingRecordIsEmptyNotFatal() {
        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    /** A released region is forgotten, so the next join retries it rather than skipping it forever. */
    @Test
    public void removingARegionMakesTheNextJoinRetryIt() throws IOException {
        final InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(5, 5, V1);
        index.remove(5, 5);
        index.save();

        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    /** The write is atomic and leaves no {@code .part} behind for a later read to trip over. */
    @Test
    public void theWriteLeavesNoPartFile() throws IOException {
        final InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(0, 0, V1);
        index.save();

        final Path dir = root().resolve(OVERWORLD);
        assertTrue(Files.isRegularFile(dir.resolve(".injected")));
        assertFalse(Files.exists(dir.resolve(".injected.part")));
    }

    /**
     * The dimension id comes off the network and is used to build a path. It is gated exactly as every
     * other store consumer gates it, and a caller that gets null must refuse the whole operation.
     */
    @Test
    public void aMalformedDimensionIdIsRefused() {
        assertNull(InjectedIndex.open(root(), "../evil", EPOCH_VOXY, false));
        assertNull(InjectedIndex.open(root(), "", EPOCH_VOXY, false));
        assertNull(InjectedIndex.open(root(), "Minecraft:Overworld", EPOCH_VOXY, false));
    }

    /** Negative region coordinates round-trip -- they pack into the key and must come back out intact. */
    @Test
    public void negativeCoordinatesRoundTrip() throws IOException {
        final InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(-12, -34, V1);
        index.save();

        final List<long[]> entries = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).entries();
        assertEquals(1, entries.size());
        assertEquals(-12, entries.get(0)[0]);
        assertEquals(-34, entries.get(0)[1]);
        assertEquals(V1, entries.get(0)[2]);
    }
}
