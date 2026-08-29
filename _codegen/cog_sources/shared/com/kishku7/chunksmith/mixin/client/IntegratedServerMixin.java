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

package com.kishku7.chunksmith.mixin.client;

import net.minecraft.client.server.IntegratedServer;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * {@code IntegratedServer.tickServer} sets {@code paused = Minecraft.isPaused() ||
 * players.isEmpty()} and, when paused, calls {@code tickPaused()} and returns without calling
 * {@code super.tickServer}. Every hook Chunksmith hangs on {@code MinecraftServer.tickServer} is
 * therefore dead for the whole time a player has the menu open, which is exactly when players
 * leave a pre-gen running, because a paused game gives the generator the whole machine.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin implements MinecraftServerExtension {
    @Inject(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/IntegratedServer;tickPaused()V"))
    private void tickPaused(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        // Order matters. Apply the queued ticket work first, then let housekeeping flush the
        // distance manager and run the unload pass over the result, the same order the running
        // server gets at tickServer HEAD. Housekeeping first would flush a distance manager that
        // has not been told about this tick's tickets yet, costing a tick of latency per chunk on
        // the one path where throughput is the entire point.
        //
        // Without the drain (3.3.0 through 3.13.0) a paused pre-gen made NO progress at all and said
        // nothing about it (mod_support #17).
        this.chunksmith$drainTicketSafePointNow();
        this.chunksmith$runChunkSystemHousekeeping(booleanSupplier);
    }
}
