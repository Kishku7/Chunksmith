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
