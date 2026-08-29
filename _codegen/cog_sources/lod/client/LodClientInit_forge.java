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

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The LOD CLIENT entrypoint for classic FORGE (MC 1.20.1 / Forge 47).
 *
 * <p><b>This class is the side guard, and it is the loader that enforces it.</b> Forge 47's
 * {@code @Mod} has no {@code dist} parameter -- that arrived with NeoForge -- so the guard
 * cannot live on the main entrypoint. It does not have to: {@code @Mod.EventBusSubscriber}
 * takes a {@code Dist[] value()}, and FML filters subscribers by the running distribution
 * before class-loading them. On a dedicated server this class is never loaded, and neither
 * is anything it reaches ({@code ClientPlatform}, the download client, the renderer
 * adapters, {@code net.minecraft.client.*}). No runtime {@code if}, no reliance on lazy
 * constant-pool resolution, no {@code DistExecutor}.
 *
 * <p>It rides the MOD bus because {@code FMLClientSetupEvent} is a mod-bus event, which
 * also means this class is the "client setup" moment, so {@code
 * ClientPlatform.onClientSetup} on this loader simply runs the action. (That event is
 * deliberately late: Forge constructs mods in dependency order and Distant Horizons is a
 * soft dependency we declare no load order against, so our constructor can run before DH's
 * and {@code DhApi.events} would not exist yet. {@code FMLClientSetupEvent} runs after
 * every mod is constructed and still long before DH announces a level.)
 *
 * <p>The channel is not built here. {@code CsLodChannel}'s static initializer builds it
 * during mod construction on BOTH sides (the only window in which Forge's network registry
 * accepts a new SimpleChannel), and that is the mod's one registration of {@code
 * chunksmith:lod}. This class installs the client sink on it.
 */
@Mod.EventBusSubscriber(modid = "chunksmith", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LodClientInit {

    private LodClientInit() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(CsLodClientBoot::init);
    }
}
