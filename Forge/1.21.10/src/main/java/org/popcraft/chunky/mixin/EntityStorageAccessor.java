package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the SimpleRegionStorage backing the per-world entity store. From it the
 * entity-region folder is reached (worker -> RegionFileStorage -> folder) so the
 * entity-unload fix can cheaply tell whether a chunk has any persisted entity data
 * on disk WITHOUT triggering the full async read that vanilla otherwise performs.
 *
 * <p>MC 1.21.1-1.21.10: the SimpleRegionStorage refactor landed at 1.20.5, so EntityStorage
 * holds its IOWorker indirectly through a {@code simpleRegionStorage} field - there is no direct
 * {@code worker} field on EntityStorage. (The unrelated 1.21.11 change moved ChunkMap onto
 * SimpleRegionStorage; that does not affect EntityStorage, which already uses it here.)
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage chunky$getSimpleRegionStorage();
}
