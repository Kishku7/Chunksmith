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
 * <p>The tests below pin both directions, because the dangerous failure is not the slow one. Skipping a
 * region the renderer does not have leaves a permanent hole in the horizon and reports success while it
 * does it. So every ambiguity here has to resolve towards injecting again.
 */
public class InjectedIndexTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static final String OVERWORLD = "minecraft_overworld";
    private static final String NETHER = "minecraft_the_nether";

    /** Freshness tokens. Their values are meaningless -- only sameness and difference matter. */
    private static final long V1 = 0x1111_2222_3333_4444L;
    private static final long V2 = 0x5555_6666_7777_8888L;

    private static final String EPOCH_VOXY = InjectedIndex.epochFor(true, false, 1);
    private static final String EPOCH_VOXY_DH = InjectedIndex.epochFor(true, true, 1);

    private Path root() {
        return temp.getRoot().toPath();
    }

    /** The bug. What one session injected, the next session must already know about. */
    @Test
    public void lastSessionsInjectionsAreRemembered() throws IOException {
        InjectedIndex first = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        first.put(0, 0, V1);
        first.put(-3, 7, V1);
        first.save();

        InjectedIndex second = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        assertEquals(2, second.size());

        // And seeding the session set from it means those regions are not claimed again, which is the
        // entire point: no re-decode, no re-push, no CPU spent redrawing what is already on screen.
        InjectedRegions session = new InjectedRegions();
        for (long[] entry : second.entries()) {
            session.seed(OVERWORLD, (int) entry[0], (int) entry[1], entry[2]);
        }
        assertFalse(session.claim(OVERWORLD, 0, 0, V1));
        assertFalse(session.claim(OVERWORLD, -3, 7, V1));
    }

    @Test
    public void aMovedTokenIsInjectedAgain() throws IOException {
        InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(2, 2, V1);
        index.save();

        InjectedRegions session = new InjectedRegions();
        for (long[] entry : InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).entries()) {
            session.seed(OVERWORLD, (int) entry[0], (int) entry[1], entry[2]);
        }
        assertTrue("grown region", session.claim(OVERWORLD, 2, 2, V2));
    }

    @Test
    public void addingARendererDiscardsTheRecord() throws IOException {
        InjectedIndex voxyOnly = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        voxyOnly.put(0, 0, V1);
        voxyOnly.put(1, 1, V1);
        voxyOnly.save();

        assertEquals("different renderer set",
                0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY_DH, false).size());
        assertEquals("the original set still reads its own record",
                2, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    @Test
    public void reinjectOnJoinIgnoresTheRecord() throws IOException {
        InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(4, 4, V1);
        index.save();

        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, true).size());
    }

    @Test
    public void dimensionsDoNotShare() throws IOException {
        InjectedIndex overworld = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        overworld.put(0, 0, V1);
        overworld.save();

        assertEquals(0, InjectedIndex.open(root(), NETHER, EPOCH_VOXY, false).size());
    }

    @Test
    public void malformedLinesAreSkippedAndTheRestSurvives() throws IOException {
        Path dir = root().resolve(OVERWORLD);
        Files.createDirectories(dir);
        List<String> lines = Arrays.asList(
                "#epoch=" + EPOCH_VOXY,
                "0,0=" + V1,
                "not a line at all",
                "1=missing-a-coordinate",
                "2,2=not-a-number",
                "3,3=" + V2);
        Files.write(dir.resolve(".injected"), lines, StandardCharsets.US_ASCII);

        InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        assertEquals("only the two readable lines count", 2, index.size());
    }

    @Test
    public void noEpochIsDiscarded() throws IOException {
        Path dir = root().resolve(OVERWORLD);
        Files.createDirectories(dir);
        Files.write(dir.resolve(".injected"), Arrays.asList("0,0=" + V1), StandardCharsets.US_ASCII);

        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    @Test
    public void aMissingRecordIsEmptyNotFatal() {
        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    @Test
    public void removalIsRetried() throws IOException {
        InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(5, 5, V1);
        index.remove(5, 5);
        index.save();

        assertEquals(0, InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).size());
    }

    @Test
    public void noPartFileIsLeft() throws IOException {
        InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(0, 0, V1);
        index.save();

        Path dir = root().resolve(OVERWORLD);
        assertTrue(Files.isRegularFile(dir.resolve(".injected")));
        assertFalse(Files.exists(dir.resolve(".injected.part")));
    }

    @Test
    public void aMalformedDimensionIdIsRefused() {
        assertNull(InjectedIndex.open(root(), "../evil", EPOCH_VOXY, false));
        assertNull(InjectedIndex.open(root(), "", EPOCH_VOXY, false));
        assertNull(InjectedIndex.open(root(), "Minecraft:Overworld", EPOCH_VOXY, false));
    }

    @Test
    public void negativeCoordinatesRoundTrip() throws IOException {
        InjectedIndex index = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false);
        index.put(-12, -34, V1);
        index.save();

        List<long[]> entries = InjectedIndex.open(root(), OVERWORLD, EPOCH_VOXY, false).entries();
        assertEquals(1, entries.size());
        assertEquals(-12, entries.get(0)[0]);
        assertEquals(-34, entries.get(0)[1]);
        assertEquals(V1, entries.get(0)[2]);
    }
}
