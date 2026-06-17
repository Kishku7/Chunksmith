package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@link IOWorker} that backs a chunk/region storage. On 1.20.1 there is no
 * {@code SimpleRegionStorage} (that is the 26.x rename); the IOWorker is held by the older
 * {@link ChunkStorage}, and {@code ChunkMap extends ChunkStorage}, so casting a ChunkMap to
 * this accessor yields its worker.
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {
    @Accessor("worker")
    IOWorker chunky$getWorker();
}
