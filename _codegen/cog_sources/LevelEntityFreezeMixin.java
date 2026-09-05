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

import com.kishku7.chunksmith.worldenter.WorldEnterPregen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Holds every entity still during a world-enter run, on the versions with no {@code TickRateManager}.
 *
 * <p>The companion to {@link ServerLevelFreezeMixin}, which holds the rest of the world. Entities
 * need their own injection because vanilla gates them per entity inside the lambda passed to
 * {@code entityTickList.forEach}, and a synthetic lambda is an unstable mixin target -- its name
 * carries an index that moves whenever the enclosing method is recompiled. So instead of targeting
 * where vanilla makes the decision, we target the method it calls to do the work:
 * {@code Level.guardEntityTick}, which is a real named method and the single funnel every ticked
 * entity passes through.
 *
 * <p><b>This freezes the PLAYER too, which is the point.</b> From 1.20.3 up, vanilla exempts players
 * from the freeze so an operator can walk around a world stopped with {@code /tick freeze}, and
 * Chunksmith has to override that exemption with a second mixin. Here there is no exemption to
 * override: freezing the funnel freezes players with everything else, and the override is not merely
 * inapplicable below 1.20.3 -- it is unnecessary. One mixin instead of two.
 *
 * <p>{@code Level} is common to both sides, so this stops the server simulating entities and the
 * client predicting them from a single injection. The static flag reaches the client thread because
 * single-player runs the integrated server in the same JVM, which is the only situation world-enter
 * pre-generation exists in.
 *
 * <p>Gated on {@link WorldEnterPregen#isFrozen()}: outside a world-enter run this cancels nothing.
 */
@Mixin(Level.class)
public abstract class LevelEntityFreezeMixin {

    @Inject(method = "guardEntityTick", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void chunksmith$freezeEntitiesDuringWorldEnter(
            Consumer<T> ticker, T entity, CallbackInfo ci) {
        if (WorldEnterPregen.isFrozen()) {
            ci.cancel();
        }
    }
}
