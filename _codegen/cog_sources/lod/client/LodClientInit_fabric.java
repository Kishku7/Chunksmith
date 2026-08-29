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

import net.fabricmc.api.ClientModInitializer;

/**
 * Attaches the client-side LOD receiver on Fabric.
 *
 * <p>Chunksmith's server-side LOD entrypoint is {@code lod.LodInit} (a {@code
 * "main"} entrypoint) and it runs on both sides, as it must; it owns the one
 * registration of the {@code chunksmith:lod} payload type. This class only ever
 * attaches a receiver to that already-registered type.
 */
public final class LodClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CsLodClientBoot.init();
    }
}
