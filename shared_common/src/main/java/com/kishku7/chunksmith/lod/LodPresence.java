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

public final class LodPresence {

    @FunctionalInterface
    public interface Provider {
        CsLodPresenceIndex indexFor(String worldName);
    }

    private static volatile Provider provider;
    // Asked only for a task with an LOD horizon, when the main provider has nothing. The plugin publishes
    // one: it has an LOD store but deliberately no presence index for ordinary runs, whose skip
    // behaviour stays exactly as it was.
    private static volatile Provider horizonProvider;

    private LodPresence() {
    }

    public static void setProvider(Provider value) {
        provider = value;
    }

    public static CsLodPresenceIndex indexFor(String worldName) {
        Provider current = provider;
        return current == null ? null : current.indexFor(worldName);
    }

    public static void setHorizonProvider(Provider value) {
        horizonProvider = value;
    }

    /** The index for a horizon task: the main one if there is one, else the horizon-only one. */
    public static CsLodPresenceIndex horizonIndexFor(String worldName) {
        CsLodPresenceIndex index = indexFor(worldName);
        if (index != null) {
            return index;
        }
        Provider current = horizonProvider;
        return current == null ? null : current.indexFor(worldName);
    }
}
