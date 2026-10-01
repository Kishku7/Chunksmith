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

import com.kishku7.chunksmith.PlatformCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The Minecraft side of the LOD horizon (mod_support #39). {@link LodHorizon} decides geometrically which
 * chunks are LOD-only; this adds the one thing only the server can see -- players -- and is what the save
 * vetoes (chunk, entities, POI, and the storage-write backstop for C2ME) actually call.
 *
 * <p>A chunk a player has been near is theirs: it is kept, and saves normally from then on. That is marked
 * both on a timer ({@link #tick}) and at veto time, so a player who builds in the ring and walks away before
 * the next save does not lose the build.
 */
public final class HorizonGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");
    private static final int KEEP_EVERY_TICKS = 20;
    private static final Pattern C2ME_RAW_SERIALIZER = Pattern.compile("(?m)^\\s*gcFreeChunkSerializer\\s*=\\s*true\\b");

    private static volatile MinecraftServer server;
    private static int tickCounter;

    private HorizonGuard() {
    }

    /** Server started with LOD on: decide whether a horizon can run here, and say so if it cannot. */
    public static void install(MinecraftServer current) {
        server = current;
        String reason = null;
        if (PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS) {
            // Moonrise replaces the whole chunk save path, so none of our vetoes would ever run and the
            // ring would be written to disk anyway. Refuse rather than promise something we cannot keep.
            reason = "Moonrise is installed, and it saves chunks its own way; your pregen can still run with the horizon off";
        } else if (PlatformCompat.ENABLE_C2ME_TICKET_COMPAT && c2meRawSerializerOn()) {
            // C2ME's experimental serializer writes raw bytes straight to its storage thread, past every
            // write method we can veto.
            reason = "C2ME's gcFreeChunkSerializer is on, and it writes chunks past the point Chunksmith can stop; turn it off in config/c2me.toml";
        }
        LodHorizon.setUnavailableReason(reason);
        if (reason != null) {
            LOGGER.info("Chunksmith: LOD horizon unavailable on this server -- {}.", reason);
        }
    }

    private static boolean c2meRawSerializerOn() {
        try {
            Path config = Path.of("config", "c2me.toml");
            return Files.isRegularFile(config) && C2ME_RAW_SERIALIZER.matcher(Files.readString(config)).find();
        } catch (Exception e) {
            return false;
        }
    }

    /** The hot-path guard every veto checks first: false means no horizon anywhere, do nothing. */
    public static boolean armed() {
        return LodHorizon.anyArmed();
    }

    /** Server tick: mark everything around each player as kept while any horizon is armed. */
    public static void tick(MinecraftServer current) {
        if (!LodHorizon.anyArmed() || ++tickCounter % KEEP_EVERY_TICKS != 0) {
            return;
        }
        int radius = current.getPlayerList().getViewDistance() + 1;
        for (ServerLevel level : current.getAllLevels()) {
            String world = LodSupport.dimensionId(level);
            if (!LodHorizon.isArmed(world)) {
                continue;
            }
            for (ServerPlayer player : level.players()) {
                ChunkPos at = player.chunkPosition();
                LodHorizon.keepAround(world, at.getMinBlockX() >> 4, at.getMinBlockZ() >> 4, radius);
            }
        }
    }

    /**
     * The chunk veto. True means drop this chunk unsaved; it also counts it. Entities and POI ask
     * {@link #drop} instead, which does not count, so each chunk is counted once.
     */
    public static boolean discardChunk(ServerLevel level, ChunkPos pos) {
        if (!drop(level, pos)) {
            return false;
        }
        LodHorizon.noteDiscarded(LodSupport.dimensionId(level));
        return true;
    }

    /** The shared decision for all three stores. Cheap when no horizon is armed. */
    public static boolean drop(ServerLevel level, ChunkPos pos) {
        if (!LodHorizon.anyArmed() || level == null || pos == null) {
            return false;
        }
        String world = LodSupport.dimensionId(level);
        int chunkX = pos.getMinBlockX() >> 4;
        int chunkZ = pos.getMinBlockZ() >> 4;
        if (!LodHorizon.inDiscardZone(world, chunkX, chunkZ)) {
            return false;
        }
        if (playerNear(level, chunkX, chunkZ)) {
            LodHorizon.keepAround(world, chunkX, chunkZ, 0);
            return false;
        }
        return true;
    }

    /** POI storage has no level of its own; find the one it belongs to. */
    public static boolean dropPoi(Object poiManager, ChunkPos pos) {
        MinecraftServer current = server;
        if (!LodHorizon.anyArmed() || current == null) {
            return false;
        }
        for (ServerLevel level : current.getAllLevels()) {
            if (level.getPoiManager() == poiManager) {
                return drop(level, pos);
            }
        }
        return false;
    }

    private static boolean playerNear(ServerLevel level, int chunkX, int chunkZ) {
        int radius = level.getServer().getPlayerList().getViewDistance() + 1;
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            if (Math.abs((at.getMinBlockX() >> 4) - chunkX) <= radius
                    && Math.abs((at.getMinBlockZ() >> 4) - chunkZ) <= radius) {
                return true;
            }
        }
        return false;
    }
}
