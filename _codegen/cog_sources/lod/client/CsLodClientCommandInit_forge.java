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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
//[[[cog
// import cog, compat
// if compat.forge_new_eventbus(mcver):
//     cog.outl("import net.minecraftforge.eventbus.api.listener.SubscribeEvent;")
// else:
//     cog.outl("import net.minecraftforge.eventbus.api.SubscribeEvent;")
//]]]
//[[[end]]]
import net.minecraftforge.fml.common.Mod;

/**
 * Registers {@code /csclient} on classic Forge.
 *
 * <p>Side guard is {@code value = Dist.CLIENT} on the subscriber, matching
 * {@code WorldEnterClientInit} next door: FML filters subscribers by dist during the annotation SCAN,
 * before the class is loaded, so a dedicated server never loads this.
 *
 * <p><b>The annotation form works on EventBus 7 too, and that is not an assumption.</b> Verified in
 * {@code javafmllanguage-1.21.8-58.1.18.jar} and {@code -1.21.11-61.1.0.jar}: {@code FMLModContainer}
 * still calls {@code AutomaticEventSubscriber.inject}, which walks the scan data, checks each declared
 * method for {@code Modifier.isStatic} and {@code @SubscribeEvent}, and registers it -- throwing on a
 * malformed handler rather than skipping it. What EventBus 7 dropped is the INSTANCE path
 * ({@code EVENT_BUS.register(this)}), which is why {@code ChunksmithForge} adds its own handlers with
 * {@code BUS.addListener} and this file does not need to. The alternative here would have been
 * {@code RegisterClientCommandsEvent.BUS.addListener}, which is NOT dist-filtered and would have
 * forced a side guard to be invented somewhere else.
 *
 * <p><b>{@code Bus.FORGE}, not {@code Bus.MOD}.</b> {@code RegisterClientCommandsEvent} is a game-bus
 * event. Getting this wrong compiles cleanly and simply never fires.
 *
 * <p>The dispatcher is {@code CommandSourceStack} -- Forge has no separate client source type. See
 * {@link CsLodClientCommand} for why that makes the node generic.
 */
@Mod.EventBusSubscriber(modid = "chunksmith", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CsLodClientCommandInit {

    private CsLodClientCommandInit() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(CsLodClientCommand.<CommandSourceStack>build("csclient",
                (source, line) -> source.sendSuccess(() -> Component.literal(line), false)));
    }
}
