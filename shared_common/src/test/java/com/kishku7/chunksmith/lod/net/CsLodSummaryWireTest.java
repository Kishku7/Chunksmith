package com.kishku7.chunksmith.lod.net;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The two new v2 messages, ON THE WIRE -- including the sentence the whole design rests on: <b>one idle
 * sync poll is 22 bytes out and 34 bytes back.</b>
 *
 * <p>That number is not decoration. It is the reason a 30-second poll from a hundred clients is affordable,
 * and it is asserted here rather than merely claimed in a comment, so that a future change to the message
 * shape has to look this test in the eye.
 */
public class CsLodSummaryWireTest {

    /** The dimension id every normal server sends: 19 characters. */
    private static final String OVERWORLD = "minecraft_overworld";

    /** THE COST OF ASKING. id (1) + UTF length prefix (2) + "minecraft_overworld" (19) = 22 bytes. */
    @Test
    public void askingCosts22Bytes() throws IOException {
        final byte[] request = CsLodMessages.requestSummary(OVERWORLD);
        assertEquals("a sync poll must stay tiny", 22, request.length);
        assertEquals(CsLodProtocol.C2S_REQUEST_SUMMARY, request[0]);
    }

    /** THE COST OF ANSWERING. id (1) + UTF (2 + 19) + count (4) + aggregate (8) = 34 bytes. */
    @Test
    public void answeringCosts34Bytes() throws IOException {
        final byte[] reply = CsLodMessages.encode(
                new CsLodMessages.RegionSummary(OVERWORLD, 81, 0x0BAD_C0FFEE_1234L));
        assertEquals("a sync answer must stay tiny -- it is NOT an index", 34, reply.length);
        assertEquals(CsLodProtocol.S2C_SUMMARY, reply[0]);
    }

    /**
     * And for scale: the INDEX the poll exists to avoid, for the same 81 regions, is ~1.7 KB on the wire --
     * and used to cost the server 366.9 MB of humongous heap to produce. The poll is ~50x smaller on the
     * wire and unboundedly cheaper to compute.
     */
    @Test
    public void anIndexIsMuchBiggerThanASummary() throws IOException {
        final java.util.List<CsLodMessages.RegionEntry> regions = new java.util.ArrayList<>();
        for (int i = 0; i < 81; i++) {
            regions.add(new CsLodMessages.RegionEntry(i % 9, i / 9, 0x1234_5678L + i, 4_800_000L));
        }
        final byte[] index = CsLodMessages.encode(new CsLodMessages.RegionIndex(OVERWORLD, regions));
        final byte[] summary = CsLodMessages.encode(new CsLodMessages.RegionSummary(OVERWORLD, 81, 7L));

        assertTrue("an 81-region index is over a kilobyte", index.length > 1_000);
        assertTrue("and the summary that stands in for it is under 40 bytes", summary.length < 40);
    }

    @Test
    public void aSummaryRoundTrips() throws IOException {
        final CsLodMessages.RegionSummary sent =
                new CsLodMessages.RegionSummary("minecraft_the_nether", 340, -1L);
        final byte[] wire = CsLodMessages.encode(sent);

        try (DataInputStream in = CsLodMessages.reader(wire)) {
            assertEquals(CsLodProtocol.S2C_SUMMARY, in.readByte());
            final CsLodMessages.RegionSummary back = CsLodMessages.decodeRegionSummary(in);
            assertEquals(sent, back);
            assertEquals(-1L, back.aggregate());
        }
    }

    /** An empty store folds to (0, 0) and that must survive the wire like anything else. */
    @Test
    public void anEmptySummaryRoundTrips() throws IOException {
        final byte[] wire = CsLodMessages.encode(new CsLodMessages.RegionSummary(OVERWORLD, 0, 0L));
        try (DataInputStream in = CsLodMessages.reader(wire)) {
            in.readByte();
            final CsLodMessages.RegionSummary back = CsLodMessages.decodeRegionSummary(in);
            assertEquals(0, back.count());
            assertEquals(0L, back.aggregate());
        }
    }

    /** A count off the wire is still range-checked, even though nothing is allocated from it. */
    @Test
    public void aNonsenseCountIsRefused() throws IOException {
        final byte[] wire = CsLodMessages.encode(new CsLodMessages.RegionSummary(OVERWORLD, 0, 0L));
        // Stamp a negative count over the wire bytes: id(1) + len(2) + name(19) = offset 22.
        wire[22] = (byte) 0xFF;
        wire[23] = (byte) 0xFF;
        wire[24] = (byte) 0xFF;
        wire[25] = (byte) 0xFF;

        try (DataInputStream in = CsLodMessages.reader(wire)) {
            in.readByte();
            CsLodMessages.decodeRegionSummary(in);
            fail("a negative region count must be refused, not reasoned about");
        } catch (final IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("out of range"));
        }
    }

    /** v2. The bump is deliberate (see CsLodProtocol.VERSION) and is pinned so it cannot drift back. */
    @Test
    public void theProtocolIsV2() {
        assertEquals(2, CsLodProtocol.VERSION);
    }
}
