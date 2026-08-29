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

package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.CsLodChunk;
import net.minecraft.world.level.Level;

/**
 * Two independent reasons this stub exists, both hard. (1) Every non-Fabric cell: it cannot compile --
 * voxy's {@code me.cortex.voxy.commonImpl.VoxyCommon} implements {@code net.fabricmc.api.ModInitializer},
 * so referencing it from a NeoForge or Forge build fails at javac with "cannot access ModInitializer".
 * (2) Fabric 1.20.1 and Fabric 1.21.1: there is nothing to feed; upstream voxy has NEVER published a
 * build for either line (its published set jumps 1.20.4 -&gt; 1.21.6), and the only 1.20.1/1.21.1 voxy in
 * existence is an unpublished source build of a fork.
 *
 * <p>{@link #supported()} returning false is load-bearing: {@code Renderers.hasVoxy()} is gated on it, so a
 * client that somehow has a mod with the id {@code voxy} is never announced to the server as a voxy client
 * whose render distance the server would then ship LOD data for. These cells feed Distant Horizons instead.
 */
public final class VoxyTarget {

    private VoxyTarget() {
    }

    public static boolean supported() {
        return false;
    }

    public static boolean available() {
        return false;
    }

    public static int inject(Level level, CsLodChunk record) {
        return 0;
    }
}
