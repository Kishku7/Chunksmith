package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the IOWorker that backs a chunk/region storage. ChunkMap extends
 * SimpleRegionStorage, so casting a ChunkMap to this accessor yields its worker.
 */
@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {
    @Accessor("worker")
    IOWorker chunky$getWorker();
}