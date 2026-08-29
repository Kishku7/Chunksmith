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

import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a ticket's remaining lifetime, because vanilla's {@code
 * isTimedOut()} cannot see the state these tickets are stuck in: it
 * requires {@code ticksLeft} to be strictly negative.
 *
 * <p>The leaked {@code minecraft:unknown} tickets sit at exactly ZERO.
 * Vanilla's own debug string reads {@code with 0 ticks left ( out of1)}.
 * They were decremented once, early, while their chunk was briefly ready
 * for saving, then froze: {@code decreaseTicksLeft()} needs {@code
 * canTicketExpire}, which needs {@code holder.isReadyForSaving()}, never
 * true again during a heavy pre-gen. One decrement short of expiry, for
 * ever -- which is why the first purge, asking {@code isTimedOut()}, found
 * nothing. Zero or less on {@code ticksLeft} is the question we mean.
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
@Mixin(Ticket.class)
public interface TicketMixin {

    @Accessor("ticksLeft")
    long getTicksLeft();
}
