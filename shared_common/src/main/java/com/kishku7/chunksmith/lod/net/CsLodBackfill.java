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

package com.kishku7.chunksmith.lod.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How far past its own render radius a client is served, per player (mod_support #45, 4.7.0,
 * {@code lodBackgroundSend}).
 *
 * <p>A client announces the radius its renderer draws and, until 4.7.0, was served only that: regions
 * within it of where the player stands. A player who stood still after joining never received the
 * rest of the store, and one who moved got the new area only after crossing half a region. This lets
 * the radius grow instead, so the player gets what they need now first (exactly as before) and the
 * remainder afterwards, nearest first.
 *
 * <p><b>The pacing is the client's, not a timer here.</b> A client only polls the server for a summary
 * while it is idle: its tick returns before the sync poll whenever a download is in flight. So each
 * poll that arrives means the last batch is finished, and advancing the radius on that poll cannot
 * pile a second batch on top of a first. Growth pushes (the server nudging a client that the store
 * grew) are not polls and do not advance it.
 *
 * <p>One value per player, used for BOTH the summary and the index, because the two must describe the
 * same set of regions or an idle poll would find a difference, pull an index, find nothing to fetch and
 * do it again every interval ({@link CsLodIndexScan}).
 */
public final class CsLodBackfill {

    private final Map<UUID, Integer> radii = new ConcurrentHashMap<>();

    /** The radius to serve this player now: their own, or the grown one if that is larger. */
    public int radius(UUID player, int baseRadius) {
        return Math.max(baseRadius, radii.getOrDefault(player, 0));
    }

    /**
     * Grows the radius one step and returns it. Doubles, up to {@code cap}; a player who has reached the
     * cap stays there. Starting from the player's own radius means the first step is the first thing past
     * what they asked to draw.
     */
    public int advance(UUID player, int baseRadius, int cap) {
        return radii.merge(player, next(baseRadius, cap), (old, fresh) -> next(Math.max(old, baseRadius), cap));
    }

    /** One step from {@code current}: double it, never past {@code cap}, never below where it already is. */
    static int next(int current, int cap) {
        if (current >= cap) {
            return cap;
        }
        long doubled = (long) current * 2L;
        return (int) Math.min(doubled, (long) cap);
    }

    /** The player left, or the server is stopping. */
    public void forget(UUID player) {
        radii.remove(player);
    }

    public void clear() {
        radii.clear();
    }
}
