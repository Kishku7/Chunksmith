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
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * LOD from chunks players explore, on a plugin server (4.6.0, {@code lodFromExploredChunks}). See
 * {@link ExploredLod} for why and for the rule.
 *
 * <p>Bukkit has no save hook and no dirty flag, so the moment is the unload, and "anything new" is decided
 * by the store alone: a chunk the store does not hold yet is taken as it unloads. A chunk that never
 * unloads (spawn, a chunk loader) is not taken while it stays loaded; a pregen covers it.
 */
public final class ExploredLodBukkit implements Listener {

    private static final Logger LOGGER = Logger.getLogger("Chunksmith");
    private static ExploredLodBukkit instance;

    private final Config config;
    private boolean failureLogged;

    private ExploredLodBukkit(Config config) {
        this.config = config;
    }

    /** Called from onEnable, after {@link LodHorizonBukkit#enable}, which publishes the presence index. */
    public static void enable(Plugin plugin, Config config) {
        if (!LodSupport.lodEnabled(config)) {
            return;
        }
        instance = new ExploredLodBukkit(config);
        Bukkit.getPluginManager().registerEvents(instance, plugin);
    }

    public static void disable() {
        if (instance != null) {
            HandlerList.unregisterAll(instance);
            instance = null;
        }
        ExploredLod.reset();
    }

    /** MONITOR: after the horizon has decided; a chunk it drops is one whose LOD is already in the store. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        // Read live, so `cs set lodFromExploredChunks` applies without a restart.
        if (!config.isLodFromExploredChunksEnabled()) {
            return;
        }
        Chunk chunk = event.getChunk();
        try {
            CsLodPresenceIndex presence = LodPresence.horizonIndexFor(event.getWorld().getName());
            if (presence == null) {
                return;
            }
            boolean held = presence.hasLod(chunk.getX(), chunk.getZ());
            // A loaded Bukkit chunk is always fully generated; no dirty flag exists, so "dirty" is true.
            if (!ExploredLod.shouldTake(true, true, true, true, held)) {
                ExploredLod.noteHeld();
                return;
            }
            LodSupport.offer(config, event.getWorld(), chunk);
            presence.markLod(chunk.getX(), chunk.getZ());
            ExploredLod.noteTaken();
        } catch (RuntimeException e) {
            ExploredLod.noteFailed();
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warning("Chunksmith: could not add an explored chunk to the LOD store (" + e
                        + "); the chunk itself unloaded normally. Further failures are counted in /cs lod status,"
                        + " not logged.");
            }
        }
    }
}
