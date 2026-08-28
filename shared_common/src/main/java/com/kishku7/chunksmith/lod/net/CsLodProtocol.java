package com.kishku7.chunksmith.lod.net;

/**
 * Shared constants for the Chunksmith LOD protocol.
 *
 * <p>MC-agnostic on purpose: both the Chunksmith server and Chunksmith-Client (a separate mod, separate
 * repo) speak this, so it must not depend on anything loader- or version-specific.
 *
 * <p>Two transports, one protocol: in-band ({@link #CHANNEL}) shares the game connection and must yield to
 * gameplay; the HTTP backchannel at {@link #httpPort game port + 1} is the fast path.
 *
 * <p>Authentication: the in-band handshake rides a connection the player has already authenticated with
 * Mojang, so the server issues a short-lived random token bound to (uuid, ip, expiry) which the client
 * presents to the HTTP endpoint. A UUID or a name proves nothing on its own -- both are public.
 */
public final class CsLodProtocol {

    /** Plugin channel id. */
    public static final String NAMESPACE = "chunksmith";

    /** Path component of the plugin channel. */
    public static final String CHANNEL = "lod";

    /**
     * Bump on any wire change. Both ends refuse a mismatch rather than guessing.
     *
     * <p><b>v2 (3.1.0-beta-4).</b> {@code S2C_INDEX}'s layout is unchanged from v1; the meaning of its
     * {@code long} hash is not. v1 was a CRC32 of the region file's contents computed independently by both
     * ends -- see {@link CsLodRegionHash} for what that cost a live production server. v2 is an opaque
     * freshness token derived from the server's (mtime, size), which the client remembers rather than
     * recomputes. A v1 client would find every region stale every time and re-download the whole in-radius
     * store every five seconds, so the handshake refuses instead: v1 -> v2 still gets a v2
     * {@code S2C_HELLO} with {@code storeAvailable=false} so it can name the mismatch, and v2 -> v1 gets
     * silence, reported after {@code CsLodClientNet.HELLO_TIMEOUT_MILLIS}. v2 also adds
     * {@link #C2S_REQUEST_SUMMARY} / {@link #S2C_SUMMARY}, which alone would not have forced a bump.
     */
    public static final int VERSION = 2;

    /** HTTP path prefix for a region file: {@code /lod/<dim>/r.<x>.<z>.cslod}. */
    public static final String HTTP_PREFIX = "/lod/";

    /** Header carrying the handshake token on every backchannel request. */
    public static final String HEADER_TOKEN = "X-Chunksmith-Token";

    /** How long a handshake token stays valid. Refreshed by the in-band channel while the player is on. */
    public static final long TOKEN_TTL_MILLIS = 10 * 60 * 1000L;

    /** Default LOD radius, in blocks, when the client cannot tell us what its renderer is set to. */
    public static final int DEFAULT_RADIUS_BLOCKS = 256;

    // decode-time input ceilings (DoS guard)
    //
    // Every count/length below is read straight off the wire from a peer we do not trust -- a hostile server
    // can send a client packet, a hostile client a server packet -- so a decoder must refuse an out-of-range
    // count before it allocates, or one tiny packet claiming a huge count OOM-kills the receiver. Validation
    // only: the wire/disk format is byte-for-byte unchanged and VERSION does not move.

    /** Max dimensions in an {@code S2C_HELLO}. Vanilla has 3; a heavily-modded server lists a few dozen. */
    public static final int MAX_HELLO_DIMENSIONS = 4096;

    /**
     * Max region entries in a single {@code S2C_INDEX}. The server indexes only inside the client's clamped
     * radius (MAX_RADIUS_BLOCKS 16384 -> ceil(16384/512)*2+1 = 65 per side -> ~4225 regions) and a
     * per-request fetch is itself capped at 4096; 65536 is ~15x headroom.
     */
    public static final int MAX_INDEX_REGIONS = 65536;

    /**
     * Max byte length of one in-band {@code S2C_CHUNK} slice. The sender drips fixed 24 KiB slices
     * (CsLodInBandSender.SLICE_BYTES = 24 * 1024); 1 MiB is ~42x headroom and still refuses a multi-GB length.
     */
    public static final int MAX_SLICE_BYTES = 1 << 20;

    /**
     * Max entries in one CSLOD palette (block or biome). Palette indices are serialized as 1 byte
     * (palette &lt;= 256) or 2 bytes ({@code CsLodCodec.indexWidth}), so 65536 is the exact ceiling.
     */
    public static final int MAX_PALETTE_SIZE = 65536;

    /**
     * Max sections in one CSLOD record. The count rides a single unsigned byte, so it is already bounded to
     * 255, and the height limit (&lt;= 4064 blocks -> 254 sections) sits under that; 256 guards a width change.
     */
    public static final int MAX_SECTIONS = 256;

    /**
     * Max byte length of one stored CSLOD record. Uncompressed worst case ~254 sections x ~12.4 KiB +
     * palettes (~6 MiB), Deflate-compressed on disk, so 32 MiB is ~5x headroom. Bounds the
     * {@code new byte[length]} in the region store, where {@code length} comes from a region-file header
     * whose bytes may have been streamed in-band from an untrusted server.
     */
    public static final int MAX_RECORD_BYTES = 32 << 20;

    // packet ids (first byte of every in-band payload)

    /** C2S: client hello -- protocol version + which renderers it has. */
    public static final byte C2S_HELLO = 1;

    /** C2S: give me the region index for this dimension. */
    public static final byte C2S_REQUEST_INDEX = 2;

    /** C2S: send these regions in-band (backchannel unavailable). */
    public static final byte C2S_REQUEST_REGIONS = 3;

    /** C2S: stop -- the client can always stop the flow. */
    public static final byte C2S_CANCEL = 4;

    /**
     * C2S: has anything changed? -- the periodic sync (v2). An id and a dimension name: 22 bytes.
     *
     * <p>Deliberately not "send me the index": the index is the expensive thing. The client pays for a real
     * index only when {@link #S2C_SUMMARY}'s two numbers disagree.
     */
    public static final byte C2S_REQUEST_SUMMARY = 5;

    /**
     * S2C: the region index folded to (count, aggregate) -- the answer to {@link #C2S_REQUEST_SUMMARY} (v2).
     * 34 bytes. See {@link CsLodSummary} for why the aggregate is an order-independent XOR.
     */
    public static final byte S2C_SUMMARY = 105;

    /** S2C: server hello -- store availability, backchannel port (0 = none), token. */
    public static final byte S2C_HELLO = 101;

    /** S2C: region index for a dimension (region coords + content hashes). */
    public static final byte S2C_INDEX = 102;

    /** S2C: an in-band chunk record (the fallback path). */
    public static final byte S2C_CHUNK = 103;

    public static final byte S2C_DONE = 104;
    /**
     * S2C: act on the player's own LOD-client settings -- list them, show one, or set one (3.3.0).
     *
     * <p>{@code /cslod set} is typed at a server, but the settings live in the player's
     * {@code config/chunksmith-lod.properties} on their client, which on a dedicated server does not exist.
     * So the server forwards and the client reads, writes and prints its own reply. Purely additive, so no
     * {@link #VERSION} bump -- an older client drops the unknown id, which is why the server checks
     * {@code CsLodServerNet.hasLodClient} first.
     */
    public static final byte S2C_CLIENT_SETTING = 106;

    // actions carried by S2C_CLIENT_SETTING

    public static final byte SETTING_LIST = 0;

    public static final byte SETTING_SHOW = 1;

    /** Set one client setting, then report the value actually stored. */
    public static final byte SETTING_SET = 2;

    private CsLodProtocol() {
    }

    /**
     * The backchannel port, derived from the game port. Game on 25565 -> HTTP on 25566; only the default
     * -- an operator may name one (mod_support #19), see {@link #httpPort(int, int)}.
     *
     * @return the backchannel port, or 0 if the game port is at the top of the range (no room for +1)
     */
    public static int httpPort(final int gamePort) {
        final int port = gamePort + 1;
        return port > 65535 ? 0 : port;
    }

    /**
     * Resolve the backchannel port an operator actually gets: their configured port if they named one,
     * otherwise {@link #httpPort(int) the derived one}. A configured port that collides with the game port
     * is refused rather than honoured -- binding it cannot succeed anyway, and refusing lets the caller
     * report a cause instead of an anonymous bind failure.
     *
     * @return the port to bind, or 0 if there is none to be had
     */
    public static int httpPort(final int gamePort, final int configured) {
        if (configured == 0) {
            return httpPort(gamePort);
        }
        if (configured == gamePort || configured < 1024 || configured > 65535) {
            return 0;
        }
        return configured;
    }
}
