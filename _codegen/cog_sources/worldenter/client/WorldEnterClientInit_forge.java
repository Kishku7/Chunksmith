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

package com.kishku7.chunksmith.worldenter.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
//[[[cog
// import cog, compat
// if compat.forge_new_eventbus(mcver):
//     cog.outl("import net.minecraftforge.eventbus.api.listener.SubscribeEvent;")
// else:
//     cog.outl("import net.minecraftforge.eventbus.api.SubscribeEvent;")
//]]]
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
//[[[end]]]
import net.minecraftforge.fml.common.Mod;

/**
 * Classic Forge's client-side half of the world-enter pregen.
 *
 * <p>The side guard is {@code value = Dist.CLIENT} on the subscriber, the same property the Fabric
 * and NeoForge halves rely on: FML filters subscribers by dist during the annotation SCAN, before
 * the class is loaded, so on a dedicated server this class -- and the {@link WorldEnterScreen} it
 * reaches -- is never loaded at all. A runtime {@code if} would not be equivalent, because the class
 * would still be loaded, and loading a Screen subclass on a server is the crash being avoided.
 *
 * <p><b>{@code Bus.FORGE}, not {@code Bus.MOD}.</b> Classic Forge splits the two buses and the tick
 * events live on the game bus; {@code LodClientInit} next door uses {@code Bus.MOD} because
 * {@code FMLClientSetupEvent} is a lifecycle event. Getting this wrong does not fail to compile --
 * the subscriber simply never fires, which is the silent-no-op failure this codebase keeps finding.
 * Verified against the enum in {@code javafmllanguage-1.21.11-61.1.0.jar}: the constants are
 * {@code FORGE} and {@code MOD}; there is no {@code GAME} on classic Forge (that is NeoForge's name).
 *
 * <p>{@code TickEvent.ClientTickEvent.Post} is Forge's shape for the per-tick hook -- NeoForge
 * promoted it to a top-level {@code ClientTickEvent.Post}, which is why the two loaders need
 * separate files here rather than one Cog seam.
 *
 * <p><b>EventBus moved the annotation at 1.21.8.</b> EventBus 7.x puts {@code SubscribeEvent} in
 * {@code net.minecraftforge.eventbus.api.listener}; 6.x had it in {@code net.minecraftforge.eventbus.api}.
 * The import is generated from {@code compat.forge_new_eventbus} so this one file serves both Forge
 * eras. Found by building: 61.1.0 reports "package net.minecraftforge.eventbus.api does not exist",
 * and the 6.x path is still live for every Forge cell at 1.21.5 and below.
 */
@Mod.EventBusSubscriber(modid = "chunksmith", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WorldEnterClientInit {

    private WorldEnterClientInit() {
    }

    // Forge split ClientTickEvent into Pre/Post somewhere between forge 47 and 49; 47 (1.20.1) has
    // one phased event instead. Phase.END is the same moment as Post. See compat.forge_tick_event_split.
    //[[[cog
    // import cog, compat
    // if compat.forge_tick_event_split(mcver):
    //     cog.outl("    @SubscribeEvent")
    //     cog.outl("    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {")
    //     cog.outl("        WorldEnterClientHook.tick(Minecraft.getInstance());")
    // else:
    //     # `phase` is deprecated-for-removal from forge 49 on, and its replacement IS the Pre/Post
    //     # split -- which forge 47 and 48 do not have. This cell's range covers those, so there is no
    //     # non-deprecated form available to it: suppress narrowly, on the one method, with the reason.
    //     cog.outl("    @SubscribeEvent")
    //     cog.outl("    @SuppressWarnings(\"removal\")  // no Pre/Post below forge 49; phase is the only shape that spans this cell")
    //     cog.outl("    public static void onClientTick(TickEvent.ClientTickEvent event) {")
    //     cog.outl("        if (event.phase != TickEvent.Phase.END) {")
    //     cog.outl("            return;")
    //     cog.outl("        }")
    //     cog.outl("        WorldEnterClientHook.tick(Minecraft.getInstance());")
    // cog.outl("    }")
    //]]]
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        WorldEnterClientHook.tick(Minecraft.getInstance());
    }
    //[[[end]]]
}
