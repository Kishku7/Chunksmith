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

import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.Folia;
import com.kishku7.chunksmith.platform.Paper;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The LOD horizon on a plugin server (mod_support #39). There are no mixins here, so this is the best the
 * Bukkit API allows, and it is honest about the gap:
 * <ul>
 *   <li>the chunk itself: {@code ChunkUnloadEvent#setSaveChunk(false)} at unload, and on Paper the chunk's
 *       own no-save flag set the moment a new chunk loads, which Paper's chunk system also honours for
 *       autosave and the shutdown flush;</li>
 *   <li>entity files and the proto chunks around the edge are still written -- Bukkit gives no way to stop
 *       either. The chunk data, which is nearly all of the size, is not.</li>
 * </ul>
 */
public final class LodHorizonBukkit implements Listener {

    private static final Logger LOGGER = Logger.getLogger("Chunksmith");
    private static final Map<String, CsLodPresenceIndex> PRESENCE = new ConcurrentHashMap<>();

    private static LodHorizonBukkit instance;
    private BukkitTask keeper;
    private boolean noSaveFlagBroken;

    private LodHorizonBukkit() {
    }

    /** Called from onEnable. Publishes the horizon's presence index and its unload veto. */
    public static void enable(Plugin plugin, Config config) {
        if (!LodSupport.lodEnabled(config)) {
            LodHorizon.setUnavailableReason("LOD generation is off (lod-enabled: false in config.yml)");
            return;
        }
        if (Folia.isFolia()) {
            LodHorizon.setUnavailableReason("Folia is not supported");
            return;
        }
        LodPresence.setHorizonProvider(worldName -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return PRESENCE.computeIfAbsent(worldName, ignored -> new CsLodPresenceIndex(LodSupport.storeRoot(world)));
        });
        instance = new LodHorizonBukkit();
        Bukkit.getPluginManager().registerEvents(instance, plugin);
        instance.keeper = Bukkit.getScheduler().runTaskTimer(plugin, LodHorizonBukkit::keepAroundPlayers, 20L, 20L);
        LodHorizon.setUnavailableReason(null);
    }

    public static void disable() {
        if (instance != null) {
            HandlerList.unregisterAll(instance);
            if (instance.keeper != null) {
                instance.keeper.cancel();
            }
            instance = null;
        }
        LodPresence.setHorizonProvider(null);
        PRESENCE.clear();
    }

    private static void keepAroundPlayers() {
        if (!LodHorizon.anyArmed()) {
            return;
        }
        int radius = Bukkit.getViewDistance() + 1;
        for (World world : Bukkit.getWorlds()) {
            if (!LodHorizon.isArmed(world.getName())) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                Location at = player.getLocation();
                LodHorizon.keepAround(world.getName(), at.getBlockX() >> 4, at.getBlockZ() >> 4, radius);
            }
        }
    }

    private static boolean drop(Chunk chunk) {
        if (!LodHorizon.anyArmed()) {
            return false;
        }
        World world = chunk.getWorld();
        if (!LodHorizon.inDiscardZone(world.getName(), chunk.getX(), chunk.getZ())) {
            return false;
        }
        int radius = Bukkit.getViewDistance() + 1;
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            if (Math.abs((at.getBlockX() >> 4) - chunk.getX()) <= radius
                    && Math.abs((at.getBlockZ() >> 4) - chunk.getZ()) <= radius) {
                LodHorizon.keepAround(world.getName(), chunk.getX(), chunk.getZ(), 0);
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk() || !Paper.isPaper() || noSaveFlagBroken || !drop(event.getChunk())) {
            return;
        }
        // Paper keeps a per-chunk "must not save" flag that its chunk system checks on every save path,
        // including autosave. Setting it now, while the chunk is fresh, is what stops an autosave writing
        // it before it ever unloads. Reflection because it is not API; if it is not there, say so once and
        // fall back to the unload veto alone.
        try {
            World world = event.getWorld();
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Method getChunk = level.getClass().getMethod("getChunkIfLoaded", int.class, int.class);
            Object levelChunk = getChunk.invoke(level, event.getChunk().getX(), event.getChunk().getZ());
            if (levelChunk != null) {
                findField(levelChunk.getClass(), "mustNotSave").setBoolean(levelChunk, true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            noSaveFlagBroken = true;
            LOGGER.warning("Chunksmith: LOD horizon cannot mark new chunks no-save on this server (" + e
                    + "); an autosave may write some of them before they unload.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (drop(event.getChunk())) {
            event.setSaveChunk(false);
            LodHorizon.noteDiscarded(event.getWorld().getName());
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
    }
}
