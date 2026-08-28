package com.kishku7.chunksmith.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@link IOWorker} behind a chunk/region storage on MC versions where
 * {@code ChunkMap extends ChunkStorage} (1.20.1 .. 1.21.10). At 1.21.11 ChunkStorage was removed
 * and ChunkMap moved onto {@code SimpleRegionStorage}, so this file is absent there (cog-gen
 * selection, compat.has_chunk_storage_accessor) and callers use {@link SimpleRegionStorageAccessor}.
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {
    @Accessor("worker")
    IOWorker chunksmith$getWorker();
}
