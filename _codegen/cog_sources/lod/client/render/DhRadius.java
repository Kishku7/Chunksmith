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

import com.seibel.distanthorizons.api.DhApi;

public final class DhRadius {

    private DhRadius() {
    }

    /**
     * Returns DH's render distance in blocks, or 0 if unreadable. {@code chunkRenderDistance()} is chunks x
     * 16.
     */
    public static int blocks() {
        try {
            Integer chunks = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
            if (chunks == null || chunks <= 0) {
                return 0;
            }
            return chunks * 16;
        } catch (LinkageError e) {
            // A LinkageError is NOT "DH is not up yet". It means the DH that IS installed does not have
            // the config API we compiled against. This is the other first-contact call into DH (alongside
            // DhTarget.inject's overwriteChunkDataAsync), so it is where that mismatch surfaces. Rule DH
            // out for the session -- loudly, once -- and let voxy carry on.
            DhTarget.disable(e);
            return 0;
        } catch (RuntimeException e) {
            // DH is present and link-compatible but not initialized yet (DhApi.Delayed.* is still null).
            // Not fatal, not a mismatch: fall back to the default rather than guess.
            return 0;
        }
    }
}
