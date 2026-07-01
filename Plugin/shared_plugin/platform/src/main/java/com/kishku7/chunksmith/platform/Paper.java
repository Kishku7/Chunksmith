package com.kishku7.chunksmith.platform;

// platform detection centralized in Platform

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

    public static double getAverageTickTime(final Server server) {
        return server.getAverageTickTime();
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(final World world, final int x, final int z) {
        return world.getChunkAtAsync(x, z, true);
    }

    public static CompletableFuture<Boolean> teleportAsync(final Entity entity, final Location location) {
        return entity.teleportAsync(location);
    }

    // RETAIN_PASSENGERS/RETAIN_VEHICLE are @Deprecated(forRemoval) in newer Paper but kept intentionally:
    // they are required to retain passengers/vehicle on the Paper versions this jar targets, with no
    // cross-version-safe replacement available yet.
    @SuppressWarnings("removal")
    public static CompletableFuture<Boolean> teleportAsyncWithPassengers(final Entity entity, final Location location) {
        return entity.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_PASSENGERS, TeleportFlag.EntityState.RETAIN_VEHICLE);
    }
}