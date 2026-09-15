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

package com.kishku7.chunksmith.platform;

/**
 * Whether this JVM is a dedicated server or a game client hosting an integrated one.
 *
 * <p>shared_common cannot ask: {@code MinecraftServer.isDedicatedServer()} is a game class and
 * {@code LodPlatform} is per-loader generated code in another package. So each loader entrypoint
 * publishes the answer here, before the config is loaded, and shared_common reads it.
 *
 * <p>Why it matters: a dedicated server has the box to itself, and every dispatch-width number
 * Chunksmith ships was measured on one. A client is sharing those same cores with the renderer,
 * the client thread and the game -- which is why a default measured at 25 chunks in flight per
 * core landed at 100 on a two-core client and pinned it (mod_support #20, #33).
 *
 * <p><strong>Defaults to dedicated.</strong> Anything that forgets to publish -- the Bukkit
 * plugin, a test, a loader entrypoint added later -- keeps the server curve it already had, so a
 * missing call can never quietly make a real server slower. The client is the case that has to
 * announce itself.
 */
public final class ServerEnvironment {

    private static volatile boolean dedicated = true;

    private ServerEnvironment() {
    }

    /**
     * Publishes what this JVM is. Call once, from the loader entrypoint, BEFORE the config is
     * loaded -- the config's own defaults are resolved off this.
     *
     * @param isDedicated true for a dedicated server, false for a client with an integrated server
     */
    public static void setDedicated(boolean isDedicated) {
        dedicated = isDedicated;
    }

    /**
     * @return true on a dedicated server, false on a client hosting an integrated server
     */
    public static boolean isDedicated() {
        return dedicated;
    }
}
