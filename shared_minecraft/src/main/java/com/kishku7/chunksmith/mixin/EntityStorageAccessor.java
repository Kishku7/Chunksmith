package com.kishku7.chunksmith.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the SimpleRegionStorage backing the per-world entity store. From it the
 * entity-region folder is reached (worker -> RegionFileStorage -> folder) so the
 * entity-unload fix can cheaply tell whether a chunk has any persisted entity data
 * on disk WITHOUT triggering the full async read that vanilla otherwise performs.
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage chunksmith$getSimpleRegionStorage();
}
