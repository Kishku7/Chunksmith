package com.kishku7.chunksmith.lod.client;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Safely turns a server-supplied dimension id into a store subdirectory.
 *
 * <p>The dimension string arrives over the network from the Chunksmith server. A joined player is
 * authenticated with Mojang, but the SERVER they joined is not trusted to be honest or bug-free, and this
 * string is used to build a filesystem path for every region file the client writes and reads. A value
 * like {@code "../.."} would otherwise walk those writes out of the client's store root, the client
 * mirror of the traversal the server guards against on its own side.
 */
public final class CsLodStore {

    private static final Pattern DIM_DIR = Pattern.compile("[a-z0-9_.-]{1,64}");

    private CsLodStore() {
    }

    public static Path dimensionDir(Path storeRoot, String dimension) {
        if (storeRoot == null || dimension == null || dimension.isEmpty()
                || !DIM_DIR.matcher(dimension).matches()) {
            return null;
        }
        final Path root = storeRoot.normalize();
        final Path dir = root.resolve(dimension).normalize();
        return dir.startsWith(root) ? dir : null;
    }
}
