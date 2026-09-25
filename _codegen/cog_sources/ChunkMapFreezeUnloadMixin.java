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

import com.kishku7.chunksmith.util.FrozenTicketPurge;
import com.kishku7.chunksmith.worldenter.WorldEnterPregen;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.BooleanSupplier;

/**
 * Caps the unload pass's share of each tick while the world-enter freeze is on (issue #37).
 *
 * <p>{@code ChunkMap.processUnloads} runs queued unload tasks for as long as the tick has time left.
 * A chunk that is not yet ready to save re-queues itself, so with a pregen in flight the queue never
 * empties and the loop spins until the tick deadline -- every tick. On a frozen world nothing else
 * wants that time except the chunk-generation tasks that run on the main thread between ticks, so
 * they starve: thread dumps showed the server thread inside {@code processUnloads} and generation
 * down from ~40 to ~6 chunks per second the moment unloading began.
 *
 * <p>This bounds the loop to {@link FrozenTicketPurge#UNLOAD_BUDGET_NANOS} per tick, and only on the
 * tick path and only while {@link WorldEnterPregen#isFrozen()}; the flush that
 * {@code saveAllChunks} does is a different call site and is untouched. Vanilla's own backstop still
 * applies: a queue over 2000 is drained past the budget regardless.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapFreezeUnloadMixin {

    @ModifyArg(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;processUnloads(Ljava/util/function/BooleanSupplier;)V"),
            require = 0)
    private BooleanSupplier chunksmith$budgetUnloadsWhileFrozen(BooleanSupplier haveTime) {
        if (!WorldEnterPregen.isFrozen()) {
            return haveTime;
        }
        final long deadline = System.nanoTime() + FrozenTicketPurge.UNLOAD_BUDGET_NANOS;
        return () -> System.nanoTime() < deadline && haveTime.getAsBoolean();
    }
}
