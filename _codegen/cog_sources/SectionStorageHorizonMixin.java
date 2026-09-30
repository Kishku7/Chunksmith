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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The LOD horizon's POI veto (mod_support #39). Both the POI tick and the chunk save's flush remove the
 * dirty entry before calling the private write, so cancelling it drops the column cleanly. Only the POI
 * manager of a level with an armed horizon can match; every other section storage passes straight on.
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageHorizonMixin {

    //[[[cog
    // import cog, compat
    // name = "writeColumn" if compat.horizon_save_era(mcver) in ("u0", "u1") else "writeChunk"
    // cog.outl('    @Inject(method = "%s(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"), cancellable = true)' % name)
    //]]]
    //[[[end]]]
    private void chunksmith$horizonVeto(ChunkPos pos, CallbackInfo ci) {
        if (HorizonGuard.armed() && HorizonGuard.dropPoi(this, pos)) {
            ci.cancel();
        }
    }
}
