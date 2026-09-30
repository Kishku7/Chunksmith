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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The LOD horizon's entity veto (mod_support #39). It shares one predicate with the chunk veto: a chunk
 * dropped with its mobs saved would bring them back twice when it regenerates. Cancelling storeEntities is
 * clean -- the section manager does not retry it.
 */
@Mixin(EntityStorage.class)
public abstract class EntityStorageHorizonMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "storeEntities(Lnet/minecraft/world/level/entity/ChunkEntities;)V", at = @At("HEAD"), cancellable = true)
    private void chunksmith$horizonVeto(ChunkEntities<Entity> entities, CallbackInfo ci) {
        if (HorizonGuard.armed() && HorizonGuard.drop(this.level, entities.getPos())) {
            ci.cancel();
        }
    }
}
