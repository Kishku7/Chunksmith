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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The LOD horizon (4.4.0, mod_support #39): past a radius, a pregen generates chunks only for their
 * LOD and the chunks themselves are never written to disk.
 *
 * <p>This class is the one decision every save veto asks. The chunk, its entities and its POI are
 * written by three separate code paths, and if they ever disagree about a position the world ends up
 * with a dropped chunk whose mobs were saved, which duplicates them when the chunk regenerates. So there
 * is exactly one predicate, {@link #inDiscardZone}, and the platform side adds only the player check
 * (it is the only side that can see players).
 *
 * <p>What is discarded, all of it at once:
 * <ul>
 *   <li>farther from the task centre than the horizon plus {@link #SEAL_CHUNKS} -- the seal is saved
 *       normally, because a feature placed from a saved chunk writes into its neighbours, and a dropped
 *       neighbour would lose that half of the tree for good;</li>
 *   <li>not on disk before the task (existing terrain is never ours to drop);</li>
 *   <li>never near a player while the horizon was armed ({@link #keepAround}).</li>
 * </ul>
 *
 * <p>Nothing here is persisted. A horizon is armed while its task runs and for {@link #GRACE_MS} after,
 * so the task's last chunks unload under it; after that every save is vanilla again. Getting that wrong
 * in the safe direction only costs disk space.
 */
public final class LodHorizon {

    /** Chunks past the horizon that are still saved, so the feature seam lands outside it. */
    public static final int SEAL_CHUNKS = 2;
    /** How long a finished task keeps its horizon armed while its chunks unload. */
    static final long GRACE_MS = 5 * 60_000L;

    /** Answers "was this chunk on disk before the task", from the task's region cache. */
    public interface Existing {
        boolean isGenerated(int chunkX, int chunkZ);
    }

    private static final Map<String, Zone> ZONES = new ConcurrentHashMap<>();
    private static volatile boolean anyArmed;
    // Null means the platform installed its save vetoes. Anything else is the sentence the operator
    // is told when a horizon cannot run here.
    private static volatile String unavailableReason = "this Minecraft version or loader has no LOD support";

    private LodHorizon() {
    }

    private static final class Zone {
        final int centerChunkX;
        final int centerChunkZ;
        final long limitChunks;
        final boolean round;
        final Existing existing;
        final Set<Long> kept = ConcurrentHashMap.newKeySet();
        final AtomicLong discarded = new AtomicLong();
        volatile long disarmAt = Long.MAX_VALUE;

        Zone(int centerChunkX, int centerChunkZ, long limitChunks, boolean round, Existing existing) {
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.limitChunks = limitChunks;
            this.round = round;
            this.existing = existing;
        }

        boolean beyond(int chunkX, int chunkZ) {
            long dx = (long) chunkX - centerChunkX;
            long dz = (long) chunkZ - centerChunkZ;
            if (round) {
                return dx * dx + dz * dz > limitChunks * limitChunks;
            }
            return Math.max(Math.abs(dx), Math.abs(dz)) > limitChunks;
        }
    }

    /** Called by the platform once its save vetoes are in place, or with the reason they are not. */
    public static void setUnavailableReason(String reason) {
        unavailableReason = reason;
    }

    /** @return null when a horizon can run on this platform, else why it cannot */
    public static String unavailableReason() {
        return unavailableReason;
    }

    /** Chunks from the centre to the horizon itself, for a horizon given in blocks. */
    public static long horizonChunks(double horizonBlocks) {
        return (long) Math.ceil(horizonBlocks / 16d);
    }

    /**
     * Returns true when a chunk at this distance is in the LOD-only ring. Geometry only: the task uses it
     * to decide how to count and skip a chunk, before anything exists to ask the world about.
     */
    public static boolean isBeyond(int centerChunkX, int centerChunkZ, double horizonBlocks, boolean round,
                                   int chunkX, int chunkZ) {
        return new Zone(centerChunkX, centerChunkZ, horizonChunks(horizonBlocks) + SEAL_CHUNKS, round, null)
                .beyond(chunkX, chunkZ);
    }

    public static void arm(String world, int centerChunkX, int centerChunkZ, double horizonBlocks, boolean round,
                           Existing existing) {
        ZONES.put(world, new Zone(centerChunkX, centerChunkZ, horizonChunks(horizonBlocks) + SEAL_CHUNKS, round,
                existing));
        anyArmed = true;
    }

    /** The task ended. Keep vetoing while its chunks unload, then stand down. */
    public static void release(String world) {
        Zone zone = ZONES.get(world);
        if (zone != null) {
            zone.disarmAt = System.currentTimeMillis() + GRACE_MS;
        }
    }

    /** Cheap guard for the hot save paths: false means no horizon anywhere, so do nothing. */
    public static boolean anyArmed() {
        return anyArmed;
    }

    private static Zone live(String world) {
        Zone zone = ZONES.get(world);
        if (zone == null) {
            return null;
        }
        if (System.currentTimeMillis() >= zone.disarmAt) {
            ZONES.remove(world, zone);
            anyArmed = !ZONES.isEmpty();
            return null;
        }
        return zone;
    }

    /** @return true while a horizon is armed for this world */
    public static boolean isArmed(String world) {
        return live(world) != null;
    }

    /**
     * The shared predicate. True means: this chunk, its entities and its POI must all be dropped, not
     * saved. The platform must still rule out a nearby player before acting on it.
     */
    public static boolean inDiscardZone(String world, int chunkX, int chunkZ) {
        Zone zone = live(world);
        if (zone == null || !zone.beyond(chunkX, chunkZ)) {
            return false;
        }
        if (zone.kept.contains(pack(chunkX, chunkZ))) {
            return false;
        }
        return zone.existing == null || !zone.existing.isGenerated(chunkX, chunkZ);
    }

    /** A player was near: everything within the radius is theirs now and saves normally. */
    public static void keepAround(String world, int chunkX, int chunkZ, int radius) {
        Zone zone = live(world);
        if (zone == null) {
            return;
        }
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                if (zone.beyond(x, z)) {
                    zone.kept.add(pack(x, z));
                }
            }
        }
    }

    /** Counts a chunk actually dropped. Only the chunk veto counts, so each chunk is counted once. */
    public static void noteDiscarded(String world) {
        Zone zone = ZONES.get(world);
        if (zone != null) {
            zone.discarded.incrementAndGet();
        }
    }

    /** @return chunks dropped under this world's current horizon */
    public static long discarded(String world) {
        Zone zone = ZONES.get(world);
        return zone == null ? 0L : zone.discarded.get();
    }

    /** @return the worlds with a horizon armed right now */
    public static Set<String> armedWorlds() {
        ZONES.keySet().forEach(LodHorizon::live);
        return Set.copyOf(ZONES.keySet());
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }
}
