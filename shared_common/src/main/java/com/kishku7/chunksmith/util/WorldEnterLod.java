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

package com.kishku7.chunksmith.util;

import com.kishku7.chunksmith.platform.LodMode;

/**
 * Whether the world-enter pregen builds Chunksmith's LOD data while it holds the world frozen
 * ({@code worldEnterLod}: auto / on / off, default auto). mod_support #37.
 *
 * <p>AUTO builds it when a LOD renderer is installed, and not otherwise. Measured on the reporter's
 * pack (Distant Horizons 3.3.2, 16 GB): the same 263,169-chunk world-enter run took 3:48:54 with
 * Chunksmith feeding DH and 6:27:22 with that feed switched off -- DH then built its LODs from the
 * chunks itself, at a higher cost in memory and CPU. Voxy is kept on the same side, unmeasured,
 * because building for it is what Chunksmith did before the setting existed. With no renderer
 * there is nothing to feed.
 *
 * <p>ON and OFF are the user's fixed choice and are returned as given. Either way the answer only
 * matters while LOD generation itself is on ({@code lodEnabled}); this can switch it off for the
 * world-enter run, never on.
 */
public final class WorldEnterLod {

    private WorldEnterLod() {
    }

    /** True if the world-enter pregen should build LOD data. */
    public static boolean resolve(LodMode mode, boolean rendererPresent) {
        if (mode == LodMode.ON) {
            return true;
        }
        if (mode == LodMode.OFF) {
            return false;
        }
        return rendererPresent;
    }
}
