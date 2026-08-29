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
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the ticket store so Chunksmith can NAME whatever is holding a chunk open.
 *
 * <p>Everything else was measured and ruled out: our own tickets (ledger: 15 outstanding against
 * 13,663 resident chunks), the settle window (retention continued with it disabled), a starved
 * unload pass ({@code processUnloads} consults no budget for {@code toDrop} and drains to 2000
 * regardless), and starved level propagation ({@code runAllUpdates} uses {@code Integer.MAX_VALUE}).
 * What remains is chunks at a full ticking level we did not put there, which
 * {@code TicketStorage.getTicketDebugString} names directly.
 *
 * <p>{@code ticketStorage} is package-private under that name on every supported version.
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
@Mixin(DistanceManager.class)
public interface DistanceManagerMixin {

    @Accessor("ticketStorage")
    TicketStorage getTicketStorage();
}
