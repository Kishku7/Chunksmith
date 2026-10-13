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

import com.kishku7.chunksmith.lod.ExploredLodGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LOD from explored chunks (4.6.0), the unload half. The save half is in {@link ChunkMapHorizonSaveMixin};
 * this one is for chunk systems that unload without going through {@code ChunkMap.save} (C2ME). HEAD, while
 * the chunk's sections and light are still there to extract.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelExploredLodMixin {

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("HEAD"))
    private void chunksmith$exploredLodOnUnload(LevelChunk chunk, CallbackInfo ci) {
        ExploredLodGuard.onUnload((ServerLevel) (Object) this, chunk);
    }
}
