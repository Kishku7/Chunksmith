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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.junit.Test;

/**
 * The 5.0 map flag rides on the end of the client hello as one optional byte. A 4.x client never sends it and a
 * 4.x server never reads it, so both directions of mixing versions must still parse.
 */
public class CsLodClientHelloMapFlagTest {

    private static CsLodMessages.ClientHello roundTrip(final byte[] frame) throws Exception {
        DataInputStream in = CsLodMessages.reader(frame);
        in.readByte(); // the message id the server reads before dispatching
        return CsLodMessages.decodeClientHello(in);
    }

    @Test
    public void theMapFlagSurvivesTheWire() throws Exception {
        CsLodMessages.ClientHello hello = roundTrip(CsLodMessages.encode(
                new CsLodMessages.ClientHello(CsLodProtocol.VERSION, false, false, 2048, true)));
        assertTrue(hello.hasMap());
        assertFalse(hello.hasVoxy());
        assertEquals(2048, hello.radiusBlocks());
    }

    @Test
    public void aFourPointXClientWithNoMapByteReadsAsNoMap() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_HELLO);
            out.writeInt(CsLodProtocol.VERSION);
            out.writeBoolean(true);
            out.writeBoolean(false);
            out.writeInt(4096);
        }
        CsLodMessages.ClientHello hello = roundTrip(raw.toByteArray());
        assertFalse(hello.hasMap());
        assertTrue(hello.hasVoxy());
        assertEquals(4096, hello.radiusBlocks());
    }

    @Test
    public void theFourFieldConstructorMeansNoMap() {
        assertFalse(new CsLodMessages.ClientHello(CsLodProtocol.VERSION, true, true, 256).hasMap());
    }
}
