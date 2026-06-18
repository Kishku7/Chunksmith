package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the per-world entity store's IOWorker. On MC 1.21.1-1.21.10 (the pre-1.21.11 structure)
 * {@code EntityStorage} holds its {@code IOWorker worker} directly - there is no
 * {@code SimpleRegionStorage} layer (that arrived with the 1.21.11 / 26.x rename). From the worker
 * the entity-region folder is reached (IOWorker.storage -> RegionFileStorage.folder) so the
 * entity-unload fix can tell whether a chunk has persisted entities without a full async read.
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    @Accessor("worker")
    IOWorker chunky$getEntityWorker();
}
