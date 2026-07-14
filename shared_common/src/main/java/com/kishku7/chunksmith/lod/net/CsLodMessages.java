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
 * <p>Plain bytes, no Minecraft types: the payload class on each side is a one-line wrapper around a
 * {@code byte[]}, and ALL the protocol lives here. That is what lets the Chunksmith server and
 * Chunksmith-Client -- two different mods, in two different repos -- share one implementation without
 * sharing a loader.
 *
 * <p>Every decoder below validates each count/length it reads off the wire against the ceilings in
 * {@link CsLodProtocol} BEFORE allocating anything (see the "decode-time input ceilings" block there). A
 * peer is not trusted: a tiny hostile or buggy packet claiming a huge count would otherwise OOM the
 * receiver on the very first allocation. On a violation the decoder throws {@link IOException}, which the
 * callers already log-and-drop as a malformed message.
 */
public final class CsLodMessages {

    private CsLodMessages() {
    }

    // ------------------------------------------------------------------ client hello

    /** What the client tells us on join: its protocol version, and WHICH RENDERERS it actually has. */
    public record ClientHello(int protocolVersion, boolean hasVoxy, boolean hasDh, int radiusBlocks) {
    }

    public static byte[] encode(final ClientHello hello) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_HELLO);
            out.writeInt(hello.protocolVersion());
            out.writeBoolean(hello.hasVoxy());
            out.writeBoolean(hello.hasDh());
            // The radius the CLIENT's renderer is configured for. The server follows it, whether it is
            // lower OR higher than the default -- the client knows how far it can actually draw.
            out.writeInt(hello.radiusBlocks());
        }
        return raw.toByteArray();
    }

    public static ClientHello decodeClientHello(final DataInputStream in) throws IOException {
        return new ClientHello(in.readInt(), in.readBoolean(), in.readBoolean(), in.readInt());
    }

    // ------------------------------------------------------------------ server hello

    /**
     * What the server answers with.
     *
     * @param backchannelPort the HTTP port, or 0 when there is none -- then the client uses the in-band
     *                        fallback. The client does not have to be told the ADDRESS: it is the same
     *                        host it is already connected to.
     * @param token           authenticates the client to the backchannel. Issued over THIS channel, which
     *                        the player has already authenticated with Mojang -- which is what makes it a
     *                        secret rather than a public identifier.
     */
    public record ServerHello(int protocolVersion, boolean storeAvailable, int backchannelPort,
                              String token, List<String> dimensions) {
    }

    public static byte[] encode(final ServerHello hello) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_HELLO);
            out.writeInt(hello.protocolVersion());
            out.writeBoolean(hello.storeAvailable());
            out.writeInt(hello.backchannelPort());
            out.writeUTF(hello.token() == null ? "" : hello.token());
            out.writeInt(hello.dimensions().size());
            for (final String dimension : hello.dimensions()) {
                out.writeUTF(dimension);
            }
        }
        return raw.toByteArray();
    }

    public static ServerHello decodeServerHello(final DataInputStream in) throws IOException {
        final int version = in.readInt();
        final boolean available = in.readBoolean();
        final int port = in.readInt();
        final String token = in.readUTF();
        final int count = in.readInt();
        // Bound BEFORE allocating: count is off the wire from an untrusted server.
        if (count < 0 || count > CsLodProtocol.MAX_HELLO_DIMENSIONS) {
            throw new IOException("CSLOD hello: dimension count " + count + " out of range [0, "
                    + CsLodProtocol.MAX_HELLO_DIMENSIONS + "]");
        }
        // Do not presize from the wire count -- grow as entries actually arrive, so a short packet that
        // over-claims hits EOF harmlessly instead of pre-allocating a large backing array.
        final List<String> dimensions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            dimensions.add(in.readUTF());
        }
        return new ServerHello(version, available, port, token, dimensions);
    }

    // ------------------------------------------------------------------ region index

    /**
     * One region the server holds, and a hash of its contents.
     *
     * <p>The hash is how "what do I already have" stays cheap: the client compares against its own local
     * store and asks only for what it is missing or what has changed. A re-join downloads nothing.
     */
    public record RegionEntry(int regionX, int regionZ, long hash, long sizeBytes) {
    }

    public record RegionIndex(String dimension, List<RegionEntry> regions) {
    }

    public static byte[] encode(final RegionIndex index) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_INDEX);
            out.writeUTF(index.dimension());
            out.writeInt(index.regions().size());
            for (final RegionEntry entry : index.regions()) {
                out.writeInt(entry.regionX());
                out.writeInt(entry.regionZ());
                out.writeLong(entry.hash());
                out.writeLong(entry.sizeBytes());
            }
        }
        return raw.toByteArray();
    }

    public static RegionIndex decodeRegionIndex(final DataInputStream in) throws IOException {
        final String dimension = in.readUTF();
        final int count = in.readInt();
        // Bound BEFORE allocating: count is off the wire from an untrusted server.
        if (count < 0 || count > CsLodProtocol.MAX_INDEX_REGIONS) {
            throw new IOException("CSLOD index: region count " + count + " out of range [0, "
                    + CsLodProtocol.MAX_INDEX_REGIONS + "]");
        }
        // Do not presize from the wire count -- each entry is four further reads that hit EOF if the
        // packet is short, so a lie is caught without pre-allocating.
        final List<RegionEntry> regions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            regions.add(new RegionEntry(in.readInt(), in.readInt(), in.readLong(), in.readLong()));
        }
        return new RegionIndex(dimension, regions);
    }

    // ------------------------------------------------------------------ the periodic sync (v2)

    /**
     * The server's whole in-range index, folded to two numbers.
     *
     * <p>This is what a sync poll costs. On the wire: the id (1) + the dimension as a UTF string (2 + 19 for
     * {@code minecraft_overworld}) + the count (4) + the aggregate (8) = <b>34 bytes</b>. The request that
     * asks for it is <b>22 bytes</b>. Nothing changed -> that is the entire exchange, and neither side opens
     * a region file.
     */
    public record RegionSummary(String dimension, int count, long aggregate) {
    }

    /** Ask the server whether anything has changed. 22 bytes for a normal dimension id. */
    public static byte[] requestSummary(final String dimension) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_REQUEST_SUMMARY);
            out.writeUTF(dimension);
        }
        return raw.toByteArray();
    }

    public static byte[] encode(final RegionSummary summary) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.S2C_SUMMARY);
            out.writeUTF(summary.dimension());
            out.writeInt(summary.count());
            out.writeLong(summary.aggregate());
        }
        return raw.toByteArray();
    }

    /**
     * Decode a summary.
     *
     * <p>Nothing here is allocated FROM the wire -- the count is a number we compare, never a size we
     * allocate -- so unlike the index there is no ceiling to enforce. It is still range-checked, because a
     * negative count is not a thing an honest server sends and we would rather refuse it than reason about
     * what it might mean.
     */
    public static RegionSummary decodeRegionSummary(final DataInputStream in) throws IOException {
        final String dimension = in.readUTF();
        final int count = in.readInt();
        if (count < 0 || count > CsLodProtocol.MAX_INDEX_REGIONS) {
            throw new IOException("CSLOD summary: region count " + count + " out of range [0, "
                    + CsLodProtocol.MAX_INDEX_REGIONS + "]");
        }
        return new RegionSummary(dimension, count, in.readLong());
    }

    // ------------------------------------------------------------------ simple requests

    /** Ask for a dimension's index. */
    public static byte[] requestIndex(final String dimension) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_REQUEST_INDEX);
            out.writeUTF(dimension);
        }
        return raw.toByteArray();
    }

    /** Ask for regions IN-BAND (the fallback, when the backchannel is unreachable). */
    public static byte[] requestRegions(final String dimension, final List<RegionEntry> wanted) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CsLodProtocol.C2S_REQUEST_REGIONS);
            out.writeUTF(dimension);
            out.writeInt(wanted.size());
            for (final RegionEntry entry : wanted) {
                out.writeInt(entry.regionX());
                out.writeInt(entry.regionZ());
            }
        }
        return raw.toByteArray();
    }

    // ------------------------------------------------------------------ in-band region data (the fallback)

    /**
     * One slice of a region file, sent in-band.
     *
     * <p>The fallback for a server with no open backchannel port. It rides the SAME connection as gameplay,
     * so it is deliberately slow and deliberately polite: the server drips a bounded number of slices per
     * tick. Gameplay wins; LOD fills the gaps. A player on this path waits longer -- which is the honest
     * cost of not opening a port, and is exactly why the backchannel exists.
     *
     * @param last true on the final slice of this region
     */
    public record RegionSlice(String dimension, int regionX, int regionZ, boolean last, byte[] data) {
    }

    public static byte[] encode(final RegionSlice slice) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream(slice.data().length + 64);
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

    public static RegionSlice decodeRegionSlice(final DataInputStream in) throws IOException {
        final String dimension = in.readUTF();
        final int x = in.readInt();
        final int z = in.readInt();
        final boolean last = in.readBoolean();
        final int length = in.readInt();
        // Bound BEFORE allocating: length is off the wire from an untrusted server. An honest slice is at
        // most the 24 KiB drip (see MAX_SLICE_BYTES); do not new byte[length] on a hostile huge value.
        if (length < 0 || length > CsLodProtocol.MAX_SLICE_BYTES) {
            throw new IOException("CSLOD slice: payload length " + length + " out of range [0, "
                    + CsLodProtocol.MAX_SLICE_BYTES + "]");
        }
        final byte[] data = new byte[length];
        in.readFully(data);
        return new RegionSlice(dimension, x, z, last, data);
    }

    /** Everything you asked for has been sent. */
    public static byte[] done() {
        return new byte[]{CsLodProtocol.S2C_DONE};
    }

    /** Stop. The client can always stop the flow. */
    public static byte[] cancel() {
        return new byte[]{CsLodProtocol.C2S_CANCEL};
    }

    /** Read the leading message id. */
    public static DataInputStream reader(final byte[] payload) {
        return new DataInputStream(new ByteArrayInputStream(payload));
    }
}
