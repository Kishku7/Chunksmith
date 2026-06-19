package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@link IOWorker} held by a {@link SimpleRegionStorage}. On MC 1.21.1-1.21.10 the
 * per-world {@code EntityStorage} reaches its region IO through a {@code simpleRegionStorage} field
 * (the SimpleRegionStorage refactor landed at 1.20.5); this accessor reaches the worker inside it.
 * On this MC range ChunkMap still extends the older {@code ChunkStorage}, so the ChunkMap worker
 * path uses {@link ChunkStorageAccessor} instead - ChunkMap only moves onto SimpleRegionStorage at
 * 1.21.11.
 */
@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {
    @Accessor("worker")
    IOWorker chunky$getWorker();
}
