package com.kishku7.chunksmith.platform;

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

    public static void schedule(Plugin plugin, Location location, Runnable runnable) {
        Bukkit.getServer().getRegionScheduler().execute(plugin, location, runnable);
    }

    public static void schedule(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        Bukkit.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, runnable);
    }

    public static void schedule(Plugin plugin, Entity entity, Runnable runnable, long delay) {
        entity.getScheduler().execute(plugin, runnable, null, delay);
    }

    public static void scheduleFixed(Plugin plugin, Location location, Runnable runnable, long delay, long period) {
        Bukkit.getServer().getRegionScheduler().runAtFixedRate(plugin, location, ignored -> runnable.run(), delay, period);
    }

    public static void scheduleFixed(Plugin plugin, Entity entity, Runnable runnable, long delay, long period) {
        entity.getScheduler().runAtFixedRate(plugin, ignored -> runnable.run(), null, delay, period);
    }

    public static void scheduleFixedGlobal(Plugin plugin, Runnable runnable, long delay, long period) {
        Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> runnable.run(), delay, period);
    }

    public static void cancelTasks(Plugin plugin) {
        Bukkit.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
    }

    public static boolean isTickThread(@NotNull Location location) {
        return Bukkit.getServer().isOwnedByCurrentRegion(location);
    }

    public static boolean isTickThread(World world, int chunkX, int chunkZ) {
        return Bukkit.getServer().isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static boolean isTickThread(Entity entity) {
        return Bukkit.getServer().isOwnedByCurrentRegion(entity);
    }

    public static void onServerInit(Plugin plugin, Runnable runnable) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onRegionisedServerInit(RegionizedServerInitEvent event) {
                runnable.run();
            }
        }, plugin);
    }
}