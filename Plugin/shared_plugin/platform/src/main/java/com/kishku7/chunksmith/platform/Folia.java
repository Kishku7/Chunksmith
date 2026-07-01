package com.kishku7.chunksmith.platform;

// platform detection centralized in Platform

import io.papermc.paper.threadedregions.RegionizedServerInitEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class Folia {
    

    private Folia() {
    }

    public static boolean isFolia() {
        return Platform.isFolia();
    }

    public static void schedule(final Plugin plugin, final Location location, final Runnable runnable) {
        Bukkit.getServer().getRegionScheduler().execute(plugin, location, runnable);
    }

    public static void schedule(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Runnable runnable) {
        Bukkit.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, runnable);
    }

    public static void schedule(final Plugin plugin, final Entity entity, final Runnable runnable, final long delay) {
        entity.getScheduler().execute(plugin, runnable, null, delay);
    }

    public static void scheduleFixed(final Plugin plugin, final Location location, final Runnable runnable, final long delay, final long period) {
        Bukkit.getServer().getRegionScheduler().runAtFixedRate(plugin, location, ignored -> runnable.run(), delay, period);
    }

    public static void scheduleFixed(final Plugin plugin, final Entity entity, final Runnable runnable, final long delay, final long period) {
        entity.getScheduler().runAtFixedRate(plugin, ignored -> runnable.run(), null, delay, period);
    }

    public static void scheduleFixedGlobal(final Plugin plugin, final Runnable runnable, final long delay, final long period) {
        Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> runnable.run(), delay, period);
    }

    public static void cancelTasks(final Plugin plugin) {
        Bukkit.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
    }

    public static boolean isTickThread(final @NotNull Location location) {
        return Bukkit.getServer().isOwnedByCurrentRegion(location);
    }

    public static boolean isTickThread(final World world, final int chunkX, final int chunkZ) {
        return Bukkit.getServer().isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static boolean isTickThread(final Entity entity) {
        return Bukkit.getServer().isOwnedByCurrentRegion(entity);
    }

    public static void onServerInit(final Plugin plugin, final Runnable runnable) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onRegionisedServerInit(final RegionizedServerInitEvent event) {
                runnable.run();
            }
        }, plugin);
    }
}