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

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.CompletableFuture;

public final class Paper {

    private Paper() {
    }

    public static boolean isPaper() {
        return Platform.isPaper();
    }

    public static double getAverageTickTime(Server server) {
        return server.getAverageTickTime();
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z) {
        return world.getChunkAtAsync(x, z, true);
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        return entity.teleportAsync(location);
    }

    // RETAIN_PASSENGERS/RETAIN_VEHICLE are @Deprecated(forRemoval) in newer Paper but kept intentionally:
    // they are required to retain passengers/vehicle on the Paper versions this jar targets, with no
    // cross-version-safe replacement available yet.
    @SuppressWarnings("removal")
    public static CompletableFuture<Boolean> teleportAsyncWithPassengers(Entity entity, Location location) {
        return entity.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_PASSENGERS, TeleportFlag.EntityState.RETAIN_VEHICLE);
    }
}