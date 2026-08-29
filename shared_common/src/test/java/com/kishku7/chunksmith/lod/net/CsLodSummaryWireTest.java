/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.lod.net;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.util.ArrayList;
import java.util.List;

/**
 * The two new v2 messages, on the wire. That includes the number the whole design rests on: one
 * idle sync poll is 22 bytes out and 34 bytes back.
 *
 * <p>That is the reason a 30-second poll from a hundred clients is affordable, so it is asserted
 * here rather than merely claimed in a comment.
 */
public class CsLodSummaryWireTest {

    /** The dimension id every normal server sends: 19 characters. */
    private static final String OVERWORLD = "minecraft_overworld";

    // id (1) + UTF length prefix (2) + "minecraft_overworld" (19) = 22 bytes.
    @Test
    public void askingCosts22Bytes() throws IOException {
        byte[] request = CsLodMessages.requestSummary(OVERWORLD);
        assertEquals("a sync poll must stay tiny", 22, request.length);
        assertEquals(CsLodProtocol.C2S_REQUEST_SUMMARY, request[0]);
    }

    // id (1) + UTF (2 + 19) + count (4) + aggregate (8) = 34 bytes.
    @Test
    public void answeringCosts34Bytes() throws IOException {
        byte[] reply = CsLodMessages.encode(
                new CsLodMessages.RegionSummary(OVERWORLD, 81, 0x0BAD_C0FFEE_1234L));
        assertEquals("a sync answer must stay tiny", 34, reply.length);
        assertEquals(CsLodProtocol.S2C_SUMMARY, reply[0]);
    }

    /**
     * And for scale: the INDEX the poll exists to avoid, for the same 81 regions, is ~1.7 KB on
     * the wire -- and used to cost the server hundreds of megabytes of humongous heap to produce
     * (see {@code CsLodServerNet}). The poll is ~50x smaller on the wire and unboundedly cheaper
     * to compute.
     */
    @Test
    public void anIndexIsMuchBiggerThanASummary() throws IOException {
        List<CsLodMessages.RegionEntry> regions = new ArrayList<>();
        for (int i = 0; i < 81; i++) {
            regions.add(new CsLodMessages.RegionEntry(i % 9, i / 9, 0x1234_5678L + i, 4_800_000L));
        }
        byte[] index = CsLodMessages.encode(new CsLodMessages.RegionIndex(OVERWORLD, regions));
        byte[] summary = CsLodMessages.encode(new CsLodMessages.RegionSummary(OVERWORLD, 81, 7L));

        assertTrue("an 81-region index is over a kilobyte", index.length > 1_000);
        assertTrue("and the summary that stands in for it is under 40 bytes", summary.length < 40);
    }

    @Test
    public void aSummaryRoundTrips() throws IOException {
        CsLodMessages.RegionSummary sent =
                new CsLodMessages.RegionSummary("minecraft_the_nether", 340, -1L);
        byte[] wire = CsLodMessages.encode(sent);

        try (DataInputStream in = CsLodMessages.reader(wire)) {
            assertEquals(CsLodProtocol.S2C_SUMMARY, in.readByte());
            CsLodMessages.RegionSummary back = CsLodMessages.decodeRegionSummary(in);
            assertEquals(sent, back);
            assertEquals(-1L, back.aggregate());
        }
    }

    @Test
    public void anEmptySummaryRoundTrips() throws IOException {
        byte[] wire = CsLodMessages.encode(new CsLodMessages.RegionSummary(OVERWORLD, 0, 0L));
        try (DataInputStream in = CsLodMessages.reader(wire)) {
            in.readByte();
            CsLodMessages.RegionSummary back = CsLodMessages.decodeRegionSummary(in);
            assertEquals(0, back.count());
            assertEquals(0L, back.aggregate());
        }
    }

    @Test
    public void aNonsenseCountIsRefused() throws IOException {
        byte[] wire = CsLodMessages.encode(new CsLodMessages.RegionSummary(OVERWORLD, 0, 0L));
        // Stamp a negative count over the wire bytes: id(1) + len(2) + name(19) = offset 22.
        wire[22] = (byte) 0xFF;
        wire[23] = (byte) 0xFF;
        wire[24] = (byte) 0xFF;
        wire[25] = (byte) 0xFF;

        try (DataInputStream in = CsLodMessages.reader(wire)) {
            in.readByte();
            CsLodMessages.decodeRegionSummary(in);
            fail("a negative region count must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("out of range"));
        }
    }

    @Test
    public void theProtocolIsV2() {
        assertEquals(2, CsLodProtocol.VERSION);
    }
}
