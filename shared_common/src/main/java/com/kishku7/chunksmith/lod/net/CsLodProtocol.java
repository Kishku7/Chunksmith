package com.kishku7.chunksmith.lod.net;

/**
 * Shared constants for the Chunksmith LOD protocol.
 *
 * <p>MC-agnostic on purpose: BOTH the Chunksmith server and Chunksmith-Client (a separate mod, separate
 * repo) speak this, so it must not depend on anything loader- or version-specific.
 *
 * <p>Two transports, one protocol:
 * <ul>
 *   <li><b>In-band</b> ({@link #CHANNEL}) -- always available. Handshake and control ride here, and it is
 *       also the DATA fallback when the backchannel is unreachable. It shares the game connection, so it
 *       must yield to gameplay.</li>
 *   <li><b>HTTP backchannel</b> -- the fast path. The store is already plain region files, so the server
 *       does not stream: it SERVES them, with range requests, resume and parallel connections. The
 *       address is DERIVED, never configured: the game's own interface, at {@link #httpPort game port
 *       + 1}.</li>
 * </ul>
 *
 * <p>Authentication: the in-band handshake rides a connection the player has ALREADY authenticated with
 * Mojang, so the server issues a short-lived random TOKEN over it, bound to (uuid, ip, expiry). The
 * client presents that token to the HTTP endpoint. A UUID or a name proves nothing on its own -- both are
 * public -- so the token is what makes the identity checks mean anything.
 */
public final class CsLodProtocol {

    /** Plugin channel id. */
    public static final String NAMESPACE = "chunksmith";

    /** Path component of the plugin channel. */
    public static final String CHANNEL = "lod";

    /** Bump on ANY wire change. Both ends refuse a mismatch rather than guessing. */
    public static final int VERSION = 1;

    /** HTTP path prefix for a region file: {@code /lod/<dim>/r.<x>.<z>.cslod}. */
    public static final String HTTP_PREFIX = "/lod/";

    /** Header carrying the handshake token on every backchannel request. */
    public static final String HEADER_TOKEN = "X-Chunksmith-Token";

    /** How long a handshake token stays valid. Refreshed by the in-band channel while the player is on. */
    public static final long TOKEN_TTL_MILLIS = 10 * 60 * 1000L;

    /** Default LOD radius, in BLOCKS, when the client cannot tell us what its renderer is set to. */
    public static final int DEFAULT_RADIUS_BLOCKS = 256;

    // ---- decode-time input ceilings (DoS guard) ----
    //
    // Every count/length below is read straight off the wire (or off a region file whose bytes may have
    // arrived over the wire) from a peer we do NOT trust: a hostile or simply buggy server can send a
    // client packet, and a hostile client can send a server packet. A decoder MUST refuse an
    // out-of-range count BEFORE it allocates anything -- otherwise one tiny packet claiming a huge count
    // OOM-kills the receiver (or throws NegativeArraySize/IllegalArgument out of a tick task) before a
    // single byte of real data arrives. These are validation ONLY: an honest peer's messages are all far
    // under these caps, so the wire/disk format is byte-for-byte unchanged and VERSION does NOT move.
    // Each ceiling is derived below from what a LEGITIMATE message can actually contain, with generous
    // headroom so no honest message is ever refused.

    /**
     * Max dimensions listed in an {@code S2C_HELLO}. Vanilla has 3 (overworld/nether/end); datapacks and
     * mods add more, but even a heavily-modded server lists at most a few dozen. 4096 is orders of
     * magnitude above any real dimension count.
     */
    public static final int MAX_HELLO_DIMENSIONS = 4096;

    /**
     * Max region entries in a single {@code S2C_INDEX}. The server only indexes regions inside the
     * client's clamped LOD radius (server MAX_RADIUS_BLOCKS 16384 -> ceil(16384/512)*2+1 = 65 regions per
     * side -> ~4225 regions), and a per-request fetch is itself capped at 4096. 65536 (a 256-region-per-
     * side grid == a 128k-block-wide world) is ~15x headroom over the largest honest index.
     */
    public static final int MAX_INDEX_REGIONS = 65536;

    /**
     * Max byte length of one in-band {@code S2C_CHUNK} slice. The sender drips fixed 24 KiB slices
     * (CsLodInBandSender.SLICE_BYTES = 24 * 1024). 1 MiB is ~42x headroom -- it absorbs any future
     * slice-size change while still refusing the multi-GB length that would OOM the receiver.
     */
    public static final int MAX_SLICE_BYTES = 1 << 20;

    /**
     * Max entries in one CSLOD palette (block or biome). Palette indices are serialized as 1 byte
     * (palette &lt;= 256) or 2 bytes ({@code CsLodCodec.indexWidth}), so at most 65536 entries are ever
     * addressable -- a larger palette is unreadable by definition. This is the exact ceiling, not an
     * estimate.
     */
    public static final int MAX_PALETTE_SIZE = 65536;

    /**
     * Max sections in one CSLOD record. Section count rides a single unsigned byte on the wire, so it is
     * already bounded to 255; the engine's own hard height limit (world height &lt;= 4064 blocks -> 254
     * sections) sits just under that. 256 documents the ceiling and guards a future width change; it can
     * never refuse an honest record.
     */
    public static final int MAX_SECTIONS = 256;

    /**
     * Max byte length of one stored CSLOD record (a compressed chunk). The uncompressed worst case is
     * ~254 sections x ~12.4 KiB + palettes (~6 MiB); the stored payload is Deflate-compressed and so
     * smaller. 32 MiB is ~5x headroom over that worst case. This bounds the {@code new byte[length]} in
     * the region store, where {@code length} comes from a region-file header whose bytes may have been
     * streamed in-band from an untrusted server.
     */
    public static final int MAX_RECORD_BYTES = 32 << 20;

    // ---- packet ids (first byte of every in-band payload) ----

    /** C2S: client hello -- protocol version + which renderers it has. */
    public static final byte C2S_HELLO = 1;

    /** C2S: give me the region index for this dimension. */
    public static final byte C2S_REQUEST_INDEX = 2;

    /** C2S: send these regions in-band (backchannel unavailable). */
    public static final byte C2S_REQUEST_REGIONS = 3;

    /** C2S: stop -- the client can always stop the flow. */
    public static final byte C2S_CANCEL = 4;

    /** S2C: server hello -- store availability, backchannel port (0 = none), token. */
    public static final byte S2C_HELLO = 101;

    /** S2C: region index for a dimension (region coords + content hashes). */
    public static final byte S2C_INDEX = 102;

    /** S2C: an in-band chunk record (the fallback path). */
    public static final byte S2C_CHUNK = 103;

    /** S2C: that is everything you asked for. */
    public static final byte S2C_DONE = 104;

    private CsLodProtocol() {
    }

    /**
     * The backchannel port is DERIVED from the game port -- never configured, nothing for an operator to
     * set. Game on 25565 -> HTTP on 25566.
     *
     * @param gamePort the port the Minecraft server is listening on
     * @return the backchannel port, or 0 if the game port is at the top of the range (no room for +1)
     */
    public static int httpPort(final int gamePort) {
        final int port = gamePort + 1;
        return port > 65535 ? 0 : port;
    }
}
