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

import com.kishku7.chunksmith.ChunksmithBukkit;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BukkitServer implements Server {
    private final ChunksmithBukkit plugin;
    private final Map<String, Integration> integrations;

    public BukkitServer(ChunksmithBukkit plugin) {
        this.plugin = plugin;
        this.integrations = new HashMap<>();
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return integrations;
    }

    @Override
    public Optional<World> getWorld(String name) {
        org.bukkit.World world = plugin.getServer().getWorld(name);
        return Optional.ofNullable(world == null ? null : new BukkitWorld(world));
    }

    @Override
    public List<World> getWorlds() {
        List<World> worlds = new ArrayList<>();
        plugin.getServer().getWorlds().forEach(world -> worlds.add(new BukkitWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        return plugin.getServer().getMaxWorldSize();
    }

    @Override
    public Sender getConsole() {
        return new BukkitSender(plugin.getServer().getConsoleSender());
    }

    @Override
    public Collection<Player> getPlayers() {
        Collection<Player> players = new ArrayList<>();
        plugin.getServer().getOnlinePlayers().forEach(player -> players.add(new BukkitPlayer(player)));
        return players;
    }

    @Override
    public Optional<Player> getPlayer(String name) {
        return Optional.ofNullable(plugin.getServer().getPlayer(name)).map(BukkitPlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        // Tick-health signal for the adaptive I/O throttle. getAverageTickTime() is a Paper
        // addition, so route through the Paper helper when present; on plain Spigot/Bukkit
        // this returns -1 and the per-chunk latency backstop carries the throttle.
        if (Platform.isFolia()) {
            return -1.0D;
        }
        if (Platform.isPaper()) {
            return Paper.getAverageTickTime(plugin.getServer());
        }
        return -1.0D;
    }
}