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

import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

/**
 * Exposes the on-disk region folder. RegionFileStorage is final, so callers must
 * cast through (Object). The folder is read-only metadata; resolving a region file
 * path from it and reading the 4096-byte offset table directly lets the
 * entity-unload fix determine "does this chunk have persisted entities?" without
 * going through the IO executor or touching the (IO-thread-owned, non-thread-safe)
 * RegionFile cache.
 */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {
    @Accessor("folder")
    Path chunksmith$getFolder();
}
