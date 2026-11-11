/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

/** The 4.9.0 additions: the injection report, the capability bit on the server hello, and the clients readout. */
public class CsLodInjectionReportTest {

    private static DataInputStream open(byte[] message) throws IOException {
        DataInputStream in = CsLodMessages.reader(message);
        in.readByte(); // the packet id, which the receiver reads before it picks a decoder
        return in;
    }

    @Test
    public void reportRoundTrips() throws IOException {
        CsLodMessages.InjectionReport sent = new CsLodMessages.InjectionReport("distant-horizons", 4200, 3);
        byte[] wire = CsLodMessages.encode(sent);
        assertEquals(CsLodProtocol.C2S_INJECTED, wire[0]);
        assertEquals(sent, CsLodMessages.decodeInjectionReport(open(wire)));
    }

    @Test
    public void hostileReportsAreRefused() throws IOException {
        assertRefused(new CsLodMessages.InjectionReport("x".repeat(CsLodProtocol.MAX_RENDERER_NAME + 1), 1, 0));
        assertRefused(new CsLodMessages.InjectionReport("DH\nFAKE LOG LINE", 1, 0));
        assertRefused(new CsLodMessages.InjectionReport("voxy", -1, 0));
        assertRefused(new CsLodMessages.InjectionReport("voxy", 1, -5));
    }

    private static void assertRefused(CsLodMessages.InjectionReport report) throws IOException {
        try {
            CsLodMessages.decodeInjectionReport(open(CsLodMessages.encode(report)));
            fail("should have been refused: " + report);
        } catch (IOException expected) {
            // refused
        }
    }

    @Test
    public void aHelloBuiltHereAdvertisesTheCapabilityAndItSurvivesTheWire() throws IOException {
        CsLodMessages.ServerHello hello = new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, true, 25566, "tok", List.of("minecraft_overworld"), "", "world");
        assertTrue(hello.acceptsInjectionReports());
        CsLodMessages.ServerHello decoded = CsLodMessages.decodeServerHello(open(CsLodMessages.encode(hello)));
        assertEquals(hello, decoded);
        assertTrue(decoded.acceptsInjectionReports());
    }

    @Test
    public void aHelloFromAServerBeforeCapabilitiesMeansNoReports() throws IOException {
        // What a 4.8.x server sends: everything up to and including the world id, and nothing after it.
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_HELLO);
            out.writeInt(CsLodProtocol.VERSION);
            out.writeBoolean(true);
            out.writeInt(25566);
            out.writeUTF("tok");
            out.writeInt(1);
            out.writeUTF("minecraft_overworld");
            out.writeUTF("");
            out.writeUTF("world");
        }
        CsLodMessages.ServerHello decoded = CsLodMessages.decodeServerHello(open(raw.toByteArray()));
        assertFalse(decoded.acceptsInjectionReports());
        assertEquals("world", decoded.worldId());
    }

    @Test
    public void clientsReadoutNamesThePlayerAndWhatTheyReceived() {
        UUID player = UUID.randomUUID();
        try {
            CsLodClientStats.onHello(player, 4, false, true, 2048, 25566);
            CsLodClientStats.addBackchannel(player, 3L * 1024 * 1024);
            CsLodClientStats.addBackchannel(player, 1L * 1024 * 1024);
            CsLodClientStats.addInjected(player, "distant-horizons", 4000, 2);
            CsLodClientStats.addInjected(player, "distant-horizons", 200, 0);

            String[] lines = CsLodClientStats.describe("Kishku7", CsLodClientStats.snapshot(player),
                    System.currentTimeMillis());
            assertEquals(3, lines.length);
            assertTrue(lines[0], lines[0].startsWith("Kishku7: LOD protocol v4, dh, radius 2048 blocks"));
            assertTrue(lines[0], lines[0].contains("backchannel port 25566"));
            assertTrue(lines[1], lines[1].contains("2 files (4.0 MB) by backchannel"));
            assertTrue(lines[2], lines[2].contains("4200 chunks into distant-horizons over 2 batches, 2 failed"));
        } finally {
            CsLodClientStats.clear(player);
        }
    }

    @Test
    public void aVanillaClientIsSaidToBeOne() {
        String[] lines = CsLodClientStats.describe("Alex", null, 0L);
        assertEquals(1, lines.length);
        assertTrue(lines[0], lines[0].startsWith("Alex: no Chunksmith LOD client"));
    }

    @Test
    public void aLateCountDoesNotResurrectAPlayerWhoLeft() {
        UUID player = UUID.randomUUID();
        CsLodClientStats.onHello(player, 4, true, false, 1024, 0);
        CsLodClientStats.clear(player);
        CsLodClientStats.addBackchannel(player, 100L);
        CsLodClientStats.addInjected(player, "voxy", 5, 0);
        assertNull(CsLodClientStats.snapshot(player));
    }
}
