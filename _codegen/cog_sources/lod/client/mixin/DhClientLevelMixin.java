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

package com.kishku7.chunksmith.lod.client.mixin;

import com.kishku7.chunksmith.lod.client.render.DhPushGuard;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On a DH-enabled server with real-time updates on (the default), {@code
 * DhClientLevel.shouldProcessChunkUpdate} drops any chunk update for a position DH has
 * seen in the last ten minutes, and {@code overwriteChunkDataAsync} still returns success
 * (see {@link DhPushGuard}). This forces the gate open for our pushes only via a
 * thread-local flag; other updates take DH's own path.
 *
 * <p>{@code remap = false} because {@code com.seibel.*} is a plain library, not Minecraft:
 * no intermediary mappings to apply, and the method is public, so no access widener. The
 * target is named as a class rather than a {@code targets = "..."} string; same bytecode,
 * but javac checks it, so a DH refactor that moved this class fails the build instead of
 * silently no-op'ing at runtime.
 *
 * <p><b>DH is an optional soft dependency, so {@code chunksmith_lodclient.mixins.json}
 * MUST stay {@code "required": false}.</b> Mixin resolves target classes at PREPARE time,
 * and under {@code required: true} this missing class is a FATAL bootstrap error on any
 * client with no DH. It would take the game down for every voxy-only player. The injector
 * itself stays at {@code require = 1}.
 */
@Mixin(value = DhClientLevel.class, remap = false)
public class DhClientLevelMixin {

    @Inject(method = "shouldProcessChunkUpdate", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksmith$alwaysAcceptOurPushes(CallbackInfoReturnable<Boolean> cir) {
        if (DhPushGuard.isPushing()) {
            DhPushGuard.forced();
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
