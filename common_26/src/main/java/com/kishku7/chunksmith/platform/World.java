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
