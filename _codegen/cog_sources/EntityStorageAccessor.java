/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
