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

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.20.1 .. 1.21.10 only. Over that range {@code ChunkMap extends ChunkStorage}, and this accessor
 * reaches the {@link IOWorker} behind a chunk/region storage. At 1.21.11 ChunkStorage was removed and
 * ChunkMap moved onto {@code SimpleRegionStorage}, so cog-gen does not emit this file there
 * (compat.has_chunk_storage_accessor) and callers use {@link SimpleRegionStorageAccessor} instead.
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {
    @Accessor("worker")
    IOWorker chunksmith$getWorker();
}
