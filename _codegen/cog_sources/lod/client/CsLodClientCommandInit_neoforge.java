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

package com.kishku7.chunksmith.lod.client;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Registers {@code /csclient} on NeoForge.
 *
 * <p>Side guard is {@code value = Dist.CLIENT} on the subscriber: FML filters by dist during the
 * annotation scan, before the class is loaded, so a dedicated server never touches this or anything
 * it reaches. A runtime {@code if} is not equivalent -- the class would still load.
 *
 * <p>No {@code bus} element: FML 10 (NeoForge 26) dropped it, and the mod bus moved to constructor
 * injection, so game-bus is the only bus the annotation addresses. Same shape as
 * {@code WorldEnterClientInit} next door.
 *
 * <p><b>The dispatcher here is {@code CommandSourceStack}, not a client source type.</b> NeoForge and
 * Forge hand a client command the same source the server uses; only Fabric has a separate
 * {@code FabricClientCommandSource}. That asymmetry is why {@link CsLodClientCommand} is generic, and
 * why the reply below goes through {@code sendSuccess} while Fabric's goes through
 * {@code sendFeedback}.
 */
@EventBusSubscriber(modid = "chunksmith", value = Dist.CLIENT)
public final class CsLodClientCommandInit {

    private CsLodClientCommandInit() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(CsLodClientCommand.<CommandSourceStack>build("csclient",
                (source, line) -> source.sendSuccess(() -> Component.literal(line), false)));
    }
}
