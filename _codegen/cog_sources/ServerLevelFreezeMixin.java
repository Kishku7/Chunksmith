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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiConsumer;

/**
 * The tick freeze, hand-rolled, for the versions that have none.
 *
 * <p>{@code TickRateManager} arrives in 1.20.3. Below it there is nothing to call: no
 * {@code setFrozen}, no {@code runsNormally}, no {@code isEntityFrozen}. World-enter pre-generation
 * needs the world held still while it runs, so on those versions we hold it ourselves.
 *
 * <p><b>This is not a reimplementation.</b> 1.20.1's {@code ServerLevel.tick} is the same method as
 * 1.20.3's with the guards taken out -- 1.20.3 computes {@code runsNormally()} once and wraps a
 * specific set of calls in {@code if}. Nothing was reordered or renamed across that boundary. So the
 * honest description of this class is that it re-adds vanilla's own guards from outside, and the set
 * below is copied from vanilla rather than chosen by us.
 *
 * <p><b>What vanilla suppresses when frozen, and so do we:</b> the world border, the weather cycle,
 * {@code tickTime}, the scheduled block and fluid ticks, raids, block events, and the dragon fight.
 *
 * <p><b>What vanilla deliberately keeps running, and so do we:</b> {@code getChunkSource().tick(...)}
 * above all -- that is precisely why pre-generation continues while the world is held, and freezing
 * it would break the feature this exists to serve. Also the sleep block, {@code updateSkyBrightness},
 * entity discarding, block entities and the entity manager.
 *
 * <p>Entities are frozen separately, in {@link LevelEntityFreezeMixin}: vanilla gates them per entity
 * inside a lambda, and a lambda is an unstable mixin target, so we take the method it calls instead.
 *
 * <p>One deliberate parity gap: vanilla's per-entity guard also skips {@code checkDespawn}, which
 * lives in that same lambda. We do not, so a distant mob may despawn during a freeze. It is invisible
 * to the player watching the progress screen, and not worth an ordinal-indexed lambda injection that
 * would break the first time Mojang recompiles the method.
 *
 * <p>The gate is {@link WorldEnterPregen#isFrozen()}, so every redirect below is a no-op outside a
 * world-enter run and this mixin costs one boolean read per call the rest of the time.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelFreezeMixin {

    // Three of the calls vanilla guards are ServerLevel's own private/protected members, so a
    // redirect handler cannot invoke them through the instance it is handed -- this mixin is not a
    // subclass. Shadow them and call them on `this`; the redirected call site is `this.xxx()` on the
    // same object, so the two are the same invocation.
    @Shadow
    private void advanceWeatherCycle() {
        throw new AssertionError("shadow");
    }

    @Shadow
    protected abstract void tickTime();

    @Shadow
    private void runBlockEvents() {
        throw new AssertionError("shadow");
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;tick()V"))
    private void chunksmith$holdWorldBorder(WorldBorder worldBorder) {
        if (!WorldEnterPregen.isFrozen()) {
            worldBorder.tick();
        }
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;advanceWeatherCycle()V"))
    private void chunksmith$holdWeather(ServerLevel level) {
        if (!WorldEnterPregen.isFrozen()) {
            this.advanceWeatherCycle();
        }
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickTime()V"))
    private void chunksmith$holdTime(ServerLevel level) {
        if (!WorldEnterPregen.isFrozen()) {
            this.tickTime();
        }
    }

    /**
     * Covers BOTH scheduled-tick call sites -- {@code blockTicks} and {@code fluidTicks} -- because a
     * redirect with no ordinal takes every matching invocation in the method. Generic rather than raw
     * so this compiles clean under {@code -Xlint:all}; the descriptor erases to the same thing either
     * way, which is all the mixin needs to match.
     */
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
    private <T> void chunksmith$holdScheduledTicks(
            LevelTicks<T> levelTicks, long gameTime, int maxAllowed, BiConsumer<BlockPos, T> ticker) {
        if (!WorldEnterPregen.isFrozen()) {
            levelTicks.tick(gameTime, maxAllowed, ticker);
        }
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/raid/Raids;tick()V"))
    private void chunksmith$holdRaids(Raids raids) {
        if (!WorldEnterPregen.isFrozen()) {
            raids.tick();
        }
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;runBlockEvents()V"))
    private void chunksmith$holdBlockEvents(ServerLevel level) {
        if (!WorldEnterPregen.isFrozen()) {
            this.runBlockEvents();
        }
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/dimension/end/EndDragonFight;tick()V"))
    private void chunksmith$holdDragonFight(EndDragonFight dragonFight) {
        if (!WorldEnterPregen.isFrozen()) {
            dragonFight.tick();
        }
    }
}
