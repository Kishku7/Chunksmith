/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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

import com.kishku7.chunksmith.integration.Integration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Server {
    Map<String, Integration> getIntegrations();

    Optional<World> getWorld(String name);

    List<World> getWorlds();

    int getMaxWorldSize();

    Sender getConsole();

    Collection<Player> getPlayers();

    Optional<Player> getPlayer(String name);

    Config getConfig();

    /**
     * Returns the smoothed mean milliseconds-per-tick of the server
     * main thread, used as the primary feedback signal for the
     * adaptive I/O throttle. ~50 ms means a healthy 20 TPS; higher
     * means the server is falling behind. Returns a negative value on
     * platforms that cannot report it, in which case the throttle
     * falls back to its absolute per-chunk latency backstop.
     */
    default double getMillisPerTick() {
        return -1.0D;
    }
}
