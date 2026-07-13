package com.kishku7.chunksmith.lod.client;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Turns a server-supplied dimension id into a store subdirectory -- SAFELY.
 *
 * <p>The dimension string arrives over the network from the Chunksmith server. A joined player is
 * authenticated with Mojang, but the SERVER they joined is not trusted to be honest or bug-free, and this
 * string is used to build a filesystem path for every region file the client writes and reads. A value
 * like {@code "../.."} would otherwise walk those writes out of the client's store root -- the client
 * mirror of the traversal the server guards against on its own side.
 *
 * <p>This is the exact counterpart of the server's {@code CsLodServerNet.safeDimensionDir}: an honest
 * server derives the id as {@code dimensionId.replace(':','_').replace('/','_')} (e.g.
 * {@code minecraft_overworld}), which always matches {@link #DIM_DIR}; a crafted {@code ../evil} is
 * rejected by shape, and anything that still normalizes outside the store root is rejected by containment.
 *
 * <p><b>Every consumer that resolves a dimension against the store MUST go through here</b> -- the HTTP
 * downloader, the in-band reassembler, the cache check and the injector -- so the one gate cannot drift
 * between them (a field hardened in one handler and trusted in its sibling is the bug).
 */
public final class CsLodStore {

    /** An honest server always sends this shape; a "." or ".." that slips it is caught by containment. */
    private static final Pattern DIM_DIR = Pattern.compile("[a-z0-9_.-]{1,64}");

    private CsLodStore() {
    }

    /**
     * The per-dimension directory under {@code storeRoot} for a server-supplied {@code dimension}, or
     * {@code null} if the id is malformed or would escape the store root. Callers that get {@code null}
     * MUST skip the request rather than fall back to an unchecked resolve.
     */
    public static Path dimensionDir(final Path storeRoot, final String dimension) {
        if (storeRoot == null || dimension == null || dimension.isEmpty()
                || !DIM_DIR.matcher(dimension).matches()) {
            return null;
        }
        final Path root = storeRoot.normalize();
        final Path dir = root.resolve(dimension).normalize();
        return dir.startsWith(root) ? dir : null;
    }
}
