package com.kishku7.chunksmith.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.20.1 .. 1.21.10 only. Over that range {@code ChunkMap extends ChunkStorage}, and this accessor
 * reaches the {@link IOWorker} behind a chunk/region storage. At 1.21.11 ChunkStorage was removed and
 * ChunkMap moved onto {@code SimpleRegionStorage}, so cog-gen does not emit this file there
 * (compat.has_chunk_storage_accessor) and callers use {@link SimpleRegionStorageAccessor} instead.
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {
    @Accessor("worker")
    IOWorker chunksmith$getWorker();
}
