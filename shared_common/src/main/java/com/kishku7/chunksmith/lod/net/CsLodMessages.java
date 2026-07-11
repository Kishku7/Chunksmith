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
        final List<String> dimensions = new ArrayList<>(count);
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
        final List<RegionEntry> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            regions.add(new RegionEntry(in.readInt(), in.readInt(), in.readLong(), in.readLong()));
        }
        return new RegionIndex(dimension, regions);
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

    /** Stop. The client can always stop the flow. */
    public static byte[] cancel() {
        return new byte[]{CsLodProtocol.C2S_CANCEL};
    }

    /** Read the leading message id. */
    public static DataInputStream reader(final byte[] payload) {
        return new DataInputStream(new ByteArrayInputStream(payload));
    }
}
