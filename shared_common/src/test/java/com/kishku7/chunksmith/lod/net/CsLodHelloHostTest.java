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

import com.kishku7.chunksmith.command.ConfigSetting;
import com.kishku7.chunksmith.command.ConfigSettings;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.lod.CsLodWorldId;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The advertised backchannel host on the wire, and the two config keys behind it
 * (mod_support #24).
 *
 * <p>The claim these tests exist to hold down is that {@code
 * CsLodProtocol.VERSION} did not have to move. The host is written after the
 * dimension list, which was the last field a 3.15.0 client read, so the two
 * directions have to be proven separately: a new client must survive an old
 * server's shorter message, and an old client must survive a new server's longer
 * one. Neither is obvious from reading the encoder, and getting either wrong
 * strands every player on one side of an upgrade.
 */
public class CsLodHelloHostTest {

    /** A hello in the exact shape a 3.15.0 server sends: nothing after the dimension list. */
    private static byte[] oldServerHello(String... dimensions) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_HELLO);
            out.writeInt(CsLodProtocol.VERSION);
            out.writeBoolean(true);
            out.writeInt(25566);
            out.writeUTF("a-token");
            out.writeInt(dimensions.length);
            for (String dimension : dimensions) {
                out.writeUTF(dimension);
            }
        }
        byte[] all = raw.toByteArray();
        // Drop the leading message-id byte: the dispatcher reads it before handing the stream over.
        byte[] body = new byte[all.length - 1];
        System.arraycopy(all, 1, body, 0, body.length);
        return body;
    }

    private static byte[] body(CsLodMessages.ServerHello hello) throws IOException {
        byte[] all = CsLodMessages.encode(hello);
        byte[] body = new byte[all.length - 1];
        System.arraycopy(all, 1, body, 0, body.length);
        return body;
    }

    // ---------------------------------------------------------------- the wire

    @Test
    public void aHostSurvivesTheRoundTrip() throws Exception {
        CsLodMessages.ServerHello sent = new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, true, 25566, "t", List.of("minecraft_overworld"),
                "lod.example.net", "0123456789abcdef0123456789abcdef");
        CsLodMessages.ServerHello back =
                CsLodMessages.decodeServerHello(CsLodMessages.reader(body(sent)));
        assertEquals("lod.example.net", back.advertisedHost());
        assertEquals(25566, back.backchannelPort());
        assertEquals(List.of("minecraft_overworld"), back.dimensions());
    }

    @Test
    public void anOldServersHelloDecodesWithNoHost() throws Exception {
        // The forward-compatibility half: 3.16.0 client, 3.15.0 server. The message simply ends after
        // the dimensions, and reading a host there would EOF if it were not guarded on available().
        CsLodMessages.ServerHello back = CsLodMessages.decodeServerHello(
                CsLodMessages.reader(oldServerHello("minecraft_overworld", "minecraft_the_nether")));
        assertEquals("", back.advertisedHost());
        assertEquals(25566, back.backchannelPort());
        assertEquals(2, back.dimensions().size());
    }

    @Test
    public void anOldServerWithNoDimensionsAlsoDecodes() throws Exception {
        // The empty "no store" / "no renderer" hello, which is the shortest message on the wire and
        // therefore the one most likely to run off the end.
        CsLodMessages.ServerHello back =
                CsLodMessages.decodeServerHello(CsLodMessages.reader(oldServerHello()));
        assertEquals("", back.advertisedHost());
        assertTrue(back.dimensions().isEmpty());
    }

    @Test
    public void anOlderClientStopsAtItsOwnFieldAndLeavesTheRest() throws Exception {
        // The backward-compatibility half, now two deep: a 3.15.0 client stops after the dimension
        // list, a 3.16.0 one stops after the host, and 4.0.0 appended a world id behind both. The proof
        // is that every field an older client reads is still where it was, in the same order, and that
        // whatever it does not read is only ever trailing bytes.
        byte[] payload = body(new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, true, 25566, "t", List.of("d0"), "lod.example.net",
                "0123456789abcdef0123456789abcdef"));
        try (var in = CsLodMessages.reader(payload)) {
            assertEquals(CsLodProtocol.VERSION, in.readInt());
            assertTrue(in.readBoolean());
            assertEquals(25566, in.readInt());
            assertEquals("t", in.readUTF());
            assertEquals(1, in.readInt());
            assertEquals("d0", in.readUTF());
            // Where a 3.15.0 client stops. Everything past here is bytes it never asks for, and the
            // message is length-prefixed, so leaving them is not a framing error.
            assertTrue(in.available() > 0);
            assertEquals("lod.example.net", in.readUTF());
            // Where a 3.16.0 client stops, for the same reason.
            assertTrue(in.available() > 0);
            assertEquals("0123456789abcdef0123456789abcdef", in.readUTF());
            assertEquals(0, in.available());
        }
    }

    @Test
    public void theFiveArgHelloNamesNoHost() {
        assertEquals("", new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, true, 25566, "t", List.of()).advertisedHost());
    }

    // ---------------------------------------------------------------- what counts as a host

    @Test
    public void ordinaryHostsAndAddressesAreAccepted() {
        assertEquals("0.0.0.0", Input.checkHost("0.0.0.0"));
        assertEquals("127.0.0.1", Input.checkHost("127.0.0.1"));
        assertEquals("lod.example.net", Input.checkHost("lod.example.net"));
        assertEquals("my-host", Input.checkHost("my-host"));
        assertEquals("203.0.113.7", Input.checkHost("  203.0.113.7  "));
    }

    @Test
    public void ipv6IsAcceptedWithOrWithoutBrackets() {
        // An operator writes it bare in a config file; a URL needs the brackets, and the caller adds
        // them back. Storing the bracketed form would double them up at the other end.
        assertEquals("::", Input.checkHost("[::]"));
        assertEquals("2001:db8::1", Input.checkHost("2001:db8::1"));
        assertEquals("2001:db8::1", Input.checkHost("[2001:db8::1]"));
    }

    @Test
    public void rubbishIsRejectedRatherThanStored() {
        assertEquals("", Input.checkHost(null));
        assertEquals("", Input.checkHost(""));
        assertEquals("", Input.checkHost("   "));
        assertEquals("", Input.checkHost("has space"));
        assertEquals("", Input.checkHost("-leading.hyphen"));
        assertEquals("", Input.checkHost("trailing.hyphen-"));
        assertEquals("", Input.checkHost("double..dot"));
        assertEquals("", Input.checkHost("http://lod.example.net"));
        assertEquals("", Input.checkHost("lod.example.net:25566"));
        assertEquals("", Input.checkHost("a".repeat(254)));
    }

    // ---------------------------------------------------------------- the commands

    @Test
    public void bothKeysAreInTheRegistry() {
        assertTrue(ConfigSettings.find("lodBackchannelBindAddress").isPresent());
        assertTrue(ConfigSettings.find("lodBackchannelHost").isPresent());
        // Case-insensitive, the way an operator actually types it.
        assertTrue(ConfigSettings.find("lodbackchannelhost").isPresent());
    }

    @Test
    public void theKeysAreText() {
        assertEquals(ConfigSetting.Kind.TEXT,
                ConfigSettings.find("lodBackchannelHost").orElseThrow().kind());
        assertEquals(ConfigSetting.Kind.TEXT,
                ConfigSettings.find("lodBackchannelBindAddress").orElseThrow().kind());
    }

    @Test
    public void theBudgetKeyIsThereToo() {
        assertTrue(ConfigSettings.find("lodIndexBudgetMb").isPresent());
        assertFalse(ConfigSettings.find("lodIndexBudgetMbb").isPresent());
    }

    @Test
    public void theWorldIdSurvivesTheRoundTrip() throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        CsLodMessages.ServerHello sent = new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, true, 25566, "t", List.of("d0"), "", id);
        byte[] payload = body(sent);
        try (var in = CsLodMessages.reader(payload)) {
            assertEquals(id, CsLodMessages.decodeServerHello(in).worldId());
        }
    }

    @Test
    public void aHelloThatStopsAfterTheHostDecodesToAnEmptyWorldId() throws Exception {
        // The 3.x server shape: advertisedHost present, nothing after it. available() is exact here
        // because the stream is one already-framed message, so this is the real path, not a mock.
        byte[] payload = body(new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, true, 25566, "t", List.of("d0"), "lod.example.net", ""));
        try (var in = CsLodMessages.reader(payload)) {
            assertEquals("", CsLodMessages.decodeServerHello(in).worldId());
        }
    }

    @Test
    public void onlyAThirtyTwoCharHexStringIsAcceptedAsAWorldId() {
        assertTrue(CsLodWorldId.isValid("0123456789abcdef0123456789abcdef"));
        assertFalse(CsLodWorldId.isValid(""));
        assertFalse(CsLodWorldId.isValid("0123456789ABCDEF0123456789ABCDEF"));
        assertFalse(CsLodWorldId.isValid("0123456789abcdef"));
        assertFalse(CsLodWorldId.isValid(null));
    }
}
