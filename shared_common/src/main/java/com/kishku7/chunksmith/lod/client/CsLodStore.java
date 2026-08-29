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

package com.kishku7.chunksmith.lod.client;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Safely turns a server-supplied dimension id into a store subdirectory.
 *
 * <p>The dimension string arrives over the network from the Chunksmith server. A joined
 * player is authenticated with Mojang, but the SERVER they joined is not trusted to be
 * honest or bug-free, and this string is used to build a filesystem path for every region
 * file the client writes and reads. A value like {@code "../.."} would otherwise walk
 * those writes out of the client's store root, the client mirror of the traversal the
 * server guards against on its own side.
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
        Path root = storeRoot.normalize();
        Path dir = root.resolve(dimension).normalize();
        return dir.startsWith(root) ? dir : null;
    }
}
