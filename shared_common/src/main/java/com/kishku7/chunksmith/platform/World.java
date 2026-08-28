package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.platform.util.Location;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface World {
    String getName();

    String getKey();

    CompletableFuture<Boolean> isChunkGenerated(int x, int z);

    CompletableFuture<Void> getChunkAtAsync(int x, int z);

    UUID getUUID();

    int getSeaLevel();

    /**
     * Release every chunk this world is still holding open for other mods to work in.
     *
     * <p>See {@code ChunkSettleWindow}: during a pregen a chunk's ticket is kept until its neighbours
     * exist, so a mod that reacts to a new chunk on a later tick has somewhere to build. When the run
     * ends, the chunks on the edge of it have no neighbours coming, and waiting for a neighbourhood that
     * will never close would leave those tickets held forever.
     *
     * <p>Default no-op: platforms that do not manage tickets themselves (the Bukkit plugin) have nothing
     * to release.
     */
    default void settleDrain() {
    }

    /**
     * Briefly load a square of already-generated chunks so other mods can finish work on them.
     *
     * <p>The settle sweep's one primitive. See {@code SettleSweep}. The caller guarantees every chunk in
     * the square is already on disk, so this is a read, never a generation. Default no-op: platforms that
     * do not manage tickets have nothing to load.
     */
    default void settleLoad(int chunkX, int chunkZ, int radius) {
    }

    /** Let go of a square taken by {@link #settleLoad}. */
    default void settleRelease(int chunkX, int chunkZ, int radius) {
    }

    Location getSpawn();

    Border getWorldBorder();

    int getElevation(int x, int z);

    default CompletableFuture<Integer> getElevationAtAsync(int x, int z) {
        return CompletableFuture.completedFuture(this.getElevation(x, z));
    }

    int getMaxElevation();

    void playEffect(Player player, String effect);

    void playSound(Player player, String sound);

    Optional<Path> getDirectory(String name);

    default Optional<Path> getEntitiesDirectory() {
        return getDirectory("entities");
    }

    default Optional<Path> getPOIDirectory() {
        return getDirectory("poi");
    }

    default Optional<Path> getRegionDirectory() {
        return getDirectory("region");
    }

    /**
     * Number of chunk writes currently queued to disk but not yet flushed (the deferred
     * region-write backlog). Used by the generation throttle for write-queue backpressure.
     * Returns -1 when the platform cannot report it (throttle then relies on tick-health
     * and the per-chunk latency backstop only).
     */
    default long getQueuedChunkWrites() {
        return -1;
    }
}
