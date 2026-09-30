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

import com.kishku7.chunksmith.lod.HorizonGuard;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The LOD horizon's chunk veto (mod_support #39). A chunk past the horizon was generated only for its LOD,
 * which is already in the CSLOD store, so it is dropped here instead of written.
 *
 * <p>Both halves of the veto matter. It must return FALSE: saveAllChunks(true) loops while any save returns
 * true, so a true here spins the shutdown flush forever. And it must clear the unsaved flag: left set, the
 * chunk stays in the eager-save set (1.21.2+) and autosave finds it dirty again every tick. Unload, level
 * cleanup and light cleanup carry on exactly as vanilla, because they never look at the result.
 *
 * <p>HEAD of save() is the point every vanilla path shares -- unload, autosave, eager save and the shutdown
 * flush, for full and proto chunks alike. C2ME's chunk system saves on unload without calling save();
 * {@link StorageWriteHorizonMixin} is the backstop for that.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapHorizonSaveMixin {

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true)
    private void chunksmith$horizonVeto(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (!HorizonGuard.armed()) {
            return;
        }
        if (HorizonGuard.discardChunk(((ChunkMapLevelAccessor) (Object) this).chunksmith$horizonLevel(), chunk.getPos())) {
            //[[[cog
            // import cog, compat
            // cog.outl("            chunk.%s;" % compat.horizon_flag_clear(mcver))
            //]]]
            //[[[end]]]
            cir.setReturnValue(false);
        }
    }
}
