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
 * Exposes the per-world entity store's backing storage, from which the entity-region folder is
 * reached (worker -> RegionFileStorage -> folder) so the entity-unload fix can cheaply tell whether
 * a chunk has any persisted entity data on disk WITHOUT the full async read vanilla otherwise does.
 *
 * <p>COG DRIFT (AXIS B -- storage layering, drift matrix section 2a): the SimpleRegionStorage layer
 * landed at MC 1.20.5.
 * <ul>
 *   <li>ANCIENT (1.20.1, 1.20.4): EntityStorage holds its {@code IOWorker worker} DIRECTLY -- there
 *       is no SimpleRegionStorage. The accessor targets {@code worker} and returns the IOWorker; the
 *       PESM fix casts that IOWorker straight to {@link IOWorkerAccessor}.</li>
 *   <li>TRANSITIONAL .. 26: EntityStorage holds a {@code SimpleRegionStorage simpleRegionStorage};
 *       the fix reaches the worker via {@link SimpleRegionStorageAccessor}. On 1.20.1/1.20.4 the
 *       SimpleRegionStorage class does not even exist.</li>
 * </ul>
 * Cog emits the correct target field, return type, import and getter name per version
 * (compat.entity_storage_accessor_*).
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
