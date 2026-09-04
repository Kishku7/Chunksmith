/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Freezes the PLAYER too, for the duration of the world-enter pre-generation and no longer.
 *
 * <p>Vanilla exempts players from the tick freeze on purpose -- {@code isEntityFrozen} reads
 * {@code !runsNormally() && !(entity instanceof Player) && countPlayerPassengers() <= 0}, so that
 * an operator who types {@code /tick freeze} can still walk around the world they have stopped.
 * That is the right call for a debugging command. It is the wrong one here, and it went wrong in
 * two separate ways at once.
 *
 * <p><b>It looked like a bug.</b> The player was the only thing still moving in a world that was
 * otherwise held, so a reporter watched himself fall away from spawn and concluded the freeze had
 * never engaged (mod_support #20). It had. A correct freeze was being read as a broken one.
 *
 * <p><b>And it was one.</b> A default run freezes the world for eighty minutes or more, and for all
 * of it the player alone kept ticking: hunger drained, fall damage applied, and a spawn over water
 * drowned them while they watched a progress bar. Nobody had hit it only because a spawn is usually
 * safe flat ground -- which is luck, not a design.
 *
 * <p>The gate is {@link WorldEnterPregen#isFrozen()}, so this narrows to exactly the window we
 * froze and never touches {@code /tick freeze}: outside a world-enter run the vanilla answer is
 * returned untouched, players included. {@code runsNormally()} is checked as well so that if the
 * tick freeze is ever lifted without our flag being cleared, the player is released with everything
 * else rather than being the last thing left stopped.
 *
 * <p><b>Why one mixin covers both sides.</b> {@code TickRateManager} is a common class and the
 * method is called from {@code ServerLevel.tick} and {@code ClientLevel.tickEntities} alike, so the
 * server stops simulating the player and the client stops predicting them from the same injection.
 * The static flag reaches the client thread because single-player runs the integrated server in the
 * same JVM -- which is the only situation this feature exists in, and the reason it is safe to
 * depend on here and nowhere else.
 */
@Mixin(TickRateManager.class)
public abstract class TickRateManagerMixin {

    @Shadow
    public abstract boolean runsNormally();

    @Inject(method = "isEntityFrozen", at = @At("HEAD"), cancellable = true)
    private void chunksmith$freezePlayerDuringWorldEnter(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Player && WorldEnterPregen.isFrozen() && !this.runsNormally()) {
            cir.setReturnValue(true);
        }
    }
}
