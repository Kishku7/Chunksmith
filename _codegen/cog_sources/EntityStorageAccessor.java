package com.kishku7.chunksmith.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
//[[[cog
// import cog, compat
// cog.outl(compat.entity_storage_accessor_import(mcver))
//]]]
//[[[end]]]
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the per-world entity store's backing storage, the route (worker -> RegionFileStorage ->
 * folder) by which the entity-unload fix tells whether a chunk has persisted entity data on disk
 * without the full async read vanilla otherwise does.
 *
 * <p>COG DRIFT (AXIS B, drift matrix 2a): SimpleRegionStorage landed at MC 1.20.5. On 1.20.1/1.20.4
 * it does not exist. EntityStorage holds its {@code IOWorker worker} directly, so the accessor
 * targets {@code worker} and the fix casts straight to {@link IOWorkerAccessor}. From 1.20.6 on it
 * holds a {@code SimpleRegionStorage} and the worker comes via {@link SimpleRegionStorageAccessor}.
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    //[[[cog
    // import cog, compat
    // cog.outl('    @Accessor("%s")' % compat.entity_storage_accessor_field(mcver))
    // cog.outl('    %s %s();' % (compat.entity_storage_accessor_type(mcver), compat.entity_storage_accessor_getter(mcver)))
    //]]]
    //[[[end]]]
}
