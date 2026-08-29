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

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge's client-side half. The main {@code @Mod} class ({@code ChunksmithNeoForge}) carries no
 * {@code dist} and runs everywhere, as it must: it owns the ONE registration of the
 * {@code chunksmith:lod} payload type, via {@code CsLodChannel.registerPayloads(modBus)}. This class
 * registers no payload; it installs the client SINK that the clientbound handler drains into.
 */
@Mod(value = "chunksmith", dist = Dist.CLIENT)
public class LodClientInit {

    public LodClientInit(ModContainer mod, IEventBus bus, Dist dist) {
        ClientPlatform.bootstrap(bus);
        CsLodClientBoot.init();
    }
}
