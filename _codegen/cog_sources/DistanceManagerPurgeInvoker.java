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

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the protected {@code DistanceManager.purgeStaleTickets()} for ServerChunkCacheFreezeMixin
 * on 1.20.5 .. 1.21.4, where the ticket store still lives in DistanceManager
 * (compat.ticket_purge_shape == "dm"). Generated only on those cells.
 */
@Mixin(DistanceManager.class)
public interface DistanceManagerPurgeInvoker {

    @Invoker("purgeStaleTickets")
    void chunksmith$invokePurgeStaleTickets();
}
