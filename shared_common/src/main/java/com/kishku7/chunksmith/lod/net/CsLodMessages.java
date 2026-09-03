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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encoding for the in-band messages.
 *
 * <p>Plain bytes, no Minecraft types: the payload class on each side is a one-line wrapper around
 * a {@code byte[]} and all the protocol lives here, so the Chunksmith server and client halves
 * (two mods in two repos) share one implementation without sharing a loader.
 *
 * <p>Every decoder below validates each count/length it reads off the wire against the ceilings in
 * {@link CsLodProtocol} before allocating anything: a tiny hostile packet claiming a huge count
 * would otherwise OOM the receiver on the first allocation. On a violation it throws {@link
 * IOException}, which the callers already log-and-drop as a malformed message.
 */
public final class CsLodMessages {

    private CsLodMessages() {
    }

    // client hello

    /** What the client tells us on join: its protocol version, and which renderers it actually has. */
    public record ClientHello(int protocolVersion, boolean hasVoxy, boolean hasDh, int radiusBlocks) {
    }

    public static byte[] encode(ClientHello hello) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_HELLO);
            out.writeInt(hello.protocolVersion());
            out.writeBoolean(hello.hasVoxy());
            out.writeBoolean(hello.hasDh());
            // The radius the client's renderer is configured for; the server follows it, lower or higher.
            out.writeInt(hello.radiusBlocks());
        }
        return raw.toByteArray();
    }

    public static ClientHello decodeClientHello(DataInputStream in) throws IOException {
        return new ClientHello(in.readInt(), in.readBoolean(), in.readBoolean(), in.readInt());
    }

    // server hello

    /**
     * What the server answers with.
     *
     * @param backchannelPort the HTTP port, or 0 when there is none -- then the client uses the in-band
     *                        fallback. The address is the host it is already connected to.
     * @param token           authenticates the client to the backchannel. Issued over this channel, which
     *                        the player has already authenticated with Mojang.
     */
    public record ServerHello(int protocolVersion, boolean storeAvailable, int backchannelPort,
                              String token, List<String> dimensions, String advertisedHost) {

        /** A hello that names no host, so the client uses the address it connected to. */
        public ServerHello(int protocolVersion, boolean storeAvailable, int backchannelPort,
                           String token, List<String> dimensions) {
            this(protocolVersion, storeAvailable, backchannelPort, token, dimensions, "");
        }
    }

    public static byte[] encode(ServerHello hello) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_HELLO);
            out.writeInt(hello.protocolVersion());
            out.writeBoolean(hello.storeAvailable());
            out.writeInt(hello.backchannelPort());
            out.writeUTF(hello.token() == null ? "" : hello.token());
            out.writeInt(hello.dimensions().size());
            for (String dimension : hello.dimensions()) {
                out.writeUTF(dimension);
            }
            // APPENDED, after everything a 3.15.0 client reads, and that placement is the whole reason
            // CsLodProtocol.VERSION did not have to move. An older client stops after the dimension
            // list and never sees these bytes; the message is length-prefixed, so trailing content is
            // not a framing error to it. The decoder below handles the other direction.
            out.writeUTF(hello.advertisedHost() == null ? "" : hello.advertisedHost());
        }
        return raw.toByteArray();
    }

    public static ServerHello decodeServerHello(DataInputStream in) throws IOException {
        int version = in.readInt();
        boolean available = in.readBoolean();
        int port = in.readInt();
        String token = in.readUTF();
        int count = in.readInt();
        // Bound before allocating: count is off the wire from an untrusted server.
        if (count < 0 || count > CsLodProtocol.MAX_HELLO_DIMENSIONS) {
            throw new IOException("CSLOD hello: dimension count " + count + " out of range [0, "
                    + CsLodProtocol.MAX_HELLO_DIMENSIONS + "]");
        }
        // Do not presize from the wire count. Grow as entries arrive, so a short packet that over-claims
        // hits EOF harmlessly.
        List<String> dimensions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            dimensions.add(in.readUTF());
        }
        // The other half of the compatibility trick in encode(): a 3.15.0 server's hello simply ends
        // here, so read the host only if there is anything left to read. available() is exact -- the
        // stream is a ByteArrayInputStream over one already-framed message, not a socket.
        String advertisedHost = in.available() > 0 ? in.readUTF() : "";
        return new ServerHello(version, available, port, token, dimensions, advertisedHost);
    }

    // region index

    /** One region the server holds, plus its freshness token and length. See {@link CsLodRegionHash}. */
    public record RegionEntry(int regionX, int regionZ, long hash, long sizeBytes) {
    }

    public record RegionIndex(String dimension, List<RegionEntry> regions) {
    }

    public static byte[] encode(RegionIndex index) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_INDEX);
            out.writeUTF(index.dimension());
            out.writeInt(index.regions().size());
            for (RegionEntry entry : index.regions()) {
                out.writeInt(entry.regionX());
                out.writeInt(entry.regionZ());
                out.writeLong(entry.hash());
                out.writeLong(entry.sizeBytes());
            }
        }
        return raw.toByteArray();
    }

    public static RegionIndex decodeRegionIndex(DataInputStream in) throws IOException {
        String dimension = in.readUTF();
        int count = in.readInt();
        // The count is off the wire from an untrusted server, so bound it before allocating.
        if (count < 0 || count > CsLodProtocol.MAX_INDEX_REGIONS) {
            throw new IOException("CSLOD index: region count " + count + " out of range [0, "
                    + CsLodProtocol.MAX_INDEX_REGIONS + "]");
        }
        // Do not presize from the wire count: each entry is four further reads, so a lie hits EOF first.
        List<RegionEntry> regions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            regions.add(new RegionEntry(in.readInt(), in.readInt(), in.readLong(), in.readLong()));
        }
        return new RegionIndex(dimension, regions);
    }

    // the periodic sync (v2)

    /**
     * The server's whole in-range index, folded to two numbers -- what a sync poll costs. On the
     * wire: the id (1) + the dimension as a UTF string (2 + 19 for {@code minecraft_overworld}) +
     * the count (4) + the aggregate (8) = <b>34 bytes</b>. The request that asks for it is <b>22
     * bytes</b>, and neither side opens a region file.
     */
    public record RegionSummary(String dimension, int count, long aggregate) {
    }

    /** Creates the request that asks whether anything has changed. 22 bytes for a normal dimension id. */
    public static byte[] requestSummary(String dimension) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_REQUEST_SUMMARY);
            out.writeUTF(dimension);
        }
        return raw.toByteArray();
    }

    public static byte[] encode(RegionSummary summary) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_SUMMARY);
            out.writeUTF(summary.dimension());
            out.writeInt(summary.count());
            out.writeLong(summary.aggregate());
        }
        return raw.toByteArray();
    }

    /**
     * Decodes a summary. Nothing here is allocated from the wire (the count is a number we
     * compare, never a size) so unlike the index there is no ceiling to enforce. It is still
     * range-checked, because a negative count is not a thing an honest server sends.
     */
    public static RegionSummary decodeRegionSummary(DataInputStream in) throws IOException {
        String dimension = in.readUTF();
        int count = in.readInt();
        if (count < 0 || count > CsLodProtocol.MAX_INDEX_REGIONS) {
            throw new IOException("CSLOD summary: region count " + count + " out of range [0, "
                    + CsLodProtocol.MAX_INDEX_REGIONS + "]");
        }
        return new RegionSummary(dimension, count, in.readLong());
    }

    // simple requests

    /** Creates the request for a dimension's index. */
    public static byte[] requestIndex(String dimension) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_REQUEST_INDEX);
            out.writeUTF(dimension);
        }
        return raw.toByteArray();
    }

    /** Creates the request for regions in-band (the fallback, when the backchannel is unreachable). */
    public static byte[] requestRegions(String dimension, List<RegionEntry> wanted) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_REQUEST_REGIONS);
            out.writeUTF(dimension);
            out.writeInt(wanted.size());
            for (RegionEntry entry : wanted) {
                out.writeInt(entry.regionX());
                out.writeInt(entry.regionZ());
            }
        }
        return raw.toByteArray();
    }

    // in-band region data (the fallback)

    /**
     * One slice of a region file, sent in-band: the fallback for a server with no open backchannel
     * port. It rides the same connection as gameplay, so the server drips a bounded number of
     * slices per tick and a player on this path waits longer.
     */
    public record RegionSlice(String dimension, int regionX, int regionZ, boolean last, byte[] data) {
    }

    public static byte[] encode(RegionSlice slice) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(slice.data().length + 64);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_CHUNK);
            out.writeUTF(slice.dimension());
            out.writeInt(slice.regionX());
            out.writeInt(slice.regionZ());
            out.writeBoolean(slice.last());
            out.writeInt(slice.data().length);
            out.write(slice.data());
        }
        return raw.toByteArray();
    }

    public static RegionSlice decodeRegionSlice(DataInputStream in) throws IOException {
        String dimension = in.readUTF();
        int x = in.readInt();
        int z = in.readInt();
        boolean last = in.readBoolean();
        int length = in.readInt();
        // Bound before allocating: length is off the wire from an untrusted server. An honest slice is at
        // most the 24 KiB drip (see MAX_SLICE_BYTES); do not new byte[length] on a hostile huge value.
        if (length < 0 || length > CsLodProtocol.MAX_SLICE_BYTES) {
            throw new IOException("CSLOD slice: payload length " + length + " out of range [0, "
                    + CsLodProtocol.MAX_SLICE_BYTES + "]");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new RegionSlice(dimension, x, z, last, data);
    }

    public static byte[] done() {
        return new byte[]{CsLodProtocol.S2C_DONE};
    }

    public static byte[] cancel() {
        return new byte[]{CsLodProtocol.C2S_CANCEL};
    }

    /**
     * A request to act on the player's own LOD-client settings, forwarded from {@code /cslod set}.
     *
     * <p>Three fields and no list, so there is nothing to bound at decode time beyond what {@code
     * readUTF} already bounds. {@code name} and {@code value} are empty strings, never null, for
     * the actions that do not use them.
     *
     * @param action one of CsLodProtocol.SETTING_LIST / SETTING_SHOW / SETTING_SET
     */
    public record ClientSetting(byte action, String name, String value) {
    }

    public static byte[] encode(ClientSetting setting) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_CLIENT_SETTING);
            out.writeByte(setting.action());
            out.writeUTF(setting.name());
            out.writeUTF(setting.value());
        }
        return raw.toByteArray();
    }

    public static ClientSetting decodeClientSetting(DataInputStream in) throws IOException {
        byte action = in.readByte();
        if (action != CsLodProtocol.SETTING_LIST
                && action != CsLodProtocol.SETTING_SHOW
                && action != CsLodProtocol.SETTING_SET) {
            // Refuse an unknown action rather than defaulting to one. Defaulting would make a future
            // server's new verb silently perform the wrong old one on this client's config file.
            throw new IOException("CSLOD client setting: unknown action " + action);
        }
        return new ClientSetting(action, in.readUTF(), in.readUTF());
    }

    public static DataInputStream reader(byte[] payload) {
        return new DataInputStream(new ByteArrayInputStream(payload));
    }
}
