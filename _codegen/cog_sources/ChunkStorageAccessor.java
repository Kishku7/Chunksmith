package com.kishku7.chunksmith.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@link IOWorker} that backs a chunk/region storage on MC versions where
 * {@code ChunkMap extends ChunkStorage} (1.20.1 .. 1.21.10). Casting a ChunkMap to this
 * accessor yields its worker. At 1.21.11 {@code ChunkStorage} was removed and ChunkMap moved
 * onto {@code SimpleRegionStorage}, so this accessor is ABSENT on 1.21.11 / 26 and the ChunkMap
 * worker path uses {@link SimpleRegionStorageAccessor} instead.
 *
 * <p>PRESENCE is a Cog/cog-gen file-selection concern (compat.has_chunk_storage_accessor): this
 * source is copied into the generated tree and listed in chunksmith.mixins.json ONLY for the MC
 * versions that still have ChunkStorage.
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {
    @Accessor("worker")
    IOWorker chunksmith$getWorker();
}
