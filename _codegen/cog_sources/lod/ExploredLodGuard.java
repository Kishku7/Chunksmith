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

import com.kishku7.chunksmith.ChunksmithProvider;
import com.kishku7.chunksmith.platform.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LOD from chunks players explore, on a mod-loader server (4.6.0, {@code lodFromExploredChunks}). See
 * {@link ExploredLod} for why and for the rule.
 *
 * <p>Called from the HEAD of {@code ChunkMap.save(ChunkAccess)} ({@code ChunkMapHorizonSaveMixin}): the point
 * unload, autosave, eager save and the shutdown flush all pass through, so a chunk is taken whether a player
 * walks away from it or stands in it until the next autosave. HEAD rather than after the write because the
 * dirty flag is the cheap "is there anything new" test and the write clears it.
 *
 * <p>C2ME's chunk system unloads without calling {@code ChunkMap.save}; {@link #onUnload} catches those
 * chunks as they leave. Moonrise replaces the whole chunk system and is not covered.
 */
public final class ExploredLodGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");
    private static volatile boolean failureLogged;

    private ExploredLodGuard() {
    }

    /** Every chunk save. Must be cheap when there is nothing to do, and must never throw. */
    public static void onSave(ServerLevel level, ChunkAccess chunk) {
        // Cheapest tests first: most saves are of proto chunks at the edge of worldgen, or of chunks with
        // nothing new to write.
        if (!(chunk instanceof LevelChunk full) || !full.isUnsaved()) {
            return;
        }
        take(level, full, true);
    }

    /**
     * Every chunk unload ({@code ServerLevel.unload(LevelChunk)}, {@code ServerLevelExploredLodMixin}). Vanilla
     * saves first, so the save hook has already taken the chunk and this finds it held -- one presence lookup.
     * It exists for C2ME, whose chunk system unloads WITHOUT {@code ChunkMap.save}: the first explore-gate run
     * with C2ME took 0 chunks and found 1344 (autosaved, near spawn) already held. No dirty test here --
     * C2ME's own save may already have cleared the flag -- the store alone decides.
     */
    public static void onUnload(ServerLevel level, LevelChunk chunk) {
        take(level, chunk, false);
    }

    private static void take(ServerLevel level, LevelChunk full, boolean countHeld) {
        if (!ChunksmithProvider.isLoaded()) {
            return;
        }
        try {
            Config config = ChunksmithProvider.get().getConfig();
            if (!config.isLodFromExploredChunksEnabled()) {
                return;
            }
            // Null exactly when LOD generation is off for this server (lodEnabled) -- the same switch a
            // pregen obeys.
            CsLodPresenceIndex presence = LodSupport.presenceIndexFor(level.getServer(), LodSupport.dimensionId(level));
            if (presence == null) {
                return;
            }
            ChunkPos pos = full.getPos();
            int chunkX = pos.getMinBlockX() >> 4;
            int chunkZ = pos.getMinBlockZ() >> 4;
            boolean held = presence.hasLod(chunkX, chunkZ);
            if (!ExploredLod.shouldTake(true, true, true, true, held)) {
                if (countHeld) {
                    ExploredLod.noteHeld();
                }
                return;
            }
            LodSupport.offer(level, full);
            // Mark it now: the store writes asynchronously, and the next save of this chunk must not take it
            // again while the write is still queued.
            presence.markLod(chunkX, chunkZ);
            ExploredLod.noteTaken();
        } catch (RuntimeException | LinkageError e) {
            // LinkageError too: this runs inside every chunk save and unload, and a class or method missing on
            // some loader build must cost the LOD, never the save.
            ExploredLod.noteFailed();
            if (!failureLogged) {
                failureLogged = true;
                // With the trace, once: the first one is the one that says where it broke.
                LOGGER.warn("Chunksmith: could not add an explored chunk to the LOD store; the chunk itself saved"
                        + " normally. Further failures are counted in /cs lod status, not logged.", e);
            }
        }
    }
}
