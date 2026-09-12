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

package com.kishku7.chunksmith.lod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The id a client keys its copy of this world's LOD store on.
 *
 * <p>Kept out of the loader halves on purpose: the mod reaches its store through
 * {@code LevelResource.ROOT} and the Bukkit plugin through its own world folder, but both end up
 * holding a {@code <world>/chunksmith/lod} path, and both must mint the same value the same way or
 * a player moving between them re-downloads.
 *
 * <p><b>Not the seed.</b> The id goes to everyone who connects, and operators treat a seed as
 * private. It is also not a hash of the seed: two worlds can share one, and a seed-preserving
 * rebuild is exactly the case this exists to catch (mod_support #29).
 */
public final class CsLodWorldId {

    /** File name under the store root. Sits beside the dimension directories, not inside one. */
    public static final String FILE_NAME = "world-id";

    /** What a well-formed id looks like coming back off disk or off the wire. */
    private static final Pattern VALID = Pattern.compile("[0-9a-f]{32}");

    private static final Map<Path, String> CACHE = new ConcurrentHashMap<>();

    private CsLodWorldId() {
    }

    /**
     * Reads the id for a store, minting one on first call. Returns empty when the store cannot be
     * written, which a caller must treat as "this server has no id to advertise" rather than as a
     * failure: an unkeyed client still works, it just falls back to keying on our address.
     */
    public static String forStore(final Path storeRootBase) {
        if (storeRootBase == null) {
            return "";
        }
        Path key = storeRootBase.toAbsolutePath().normalize();
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String id = load(key);
        if (!id.isEmpty()) {
            CACHE.put(key, id);
        }
        return id;
    }

    /** Forgets the cached id for a store. For tests and for a server that swaps worlds in place. */
    public static void forget(final Path storeRootBase) {
        if (storeRootBase != null) {
            CACHE.remove(storeRootBase.toAbsolutePath().normalize());
        }
    }

    /** True when a value off the wire is shaped like one of ours. An empty id is not valid, it is absent. */
    public static boolean isValid(final String id) {
        return id != null && VALID.matcher(id).matches();
    }

    private static String load(final Path storeRootBase) {
        Path file = storeRootBase.resolve(FILE_NAME);
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (isValid(existing)) {
                    return existing;
                }
                // Unreadable content is not worth preserving, but do not delete someone's file either.
                // Minting over it is the recoverable choice: a bad id would orphan every client's store
                // once, a refusal would orphan it forever.
            }
            Files.createDirectories(storeRootBase);
            String minted = UUID.randomUUID().toString().replace("-", "");
            Files.writeString(file, minted + System.lineSeparator(), StandardCharsets.UTF_8);
            return minted;
        } catch (IOException e) {
            // Fail soft. The hello has to go out regardless; a missing id costs the client nothing it
            // had before 4.0.0.
            return "";
        }
    }
}
