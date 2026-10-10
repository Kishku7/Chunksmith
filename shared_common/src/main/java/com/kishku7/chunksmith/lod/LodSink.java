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

package com.kishku7.chunksmith.lod;

/**
 * Implementations must be safe to call from the server main thread and must not block.
 */
public interface LodSink {

    /**
     * Returns {@code false} when the sink is saturated. A {@code false} return is backpressure,
     * not an error: the caller must not treat the chunk as done, and should retry it later.
     */
    boolean offer(Object chunk);

    int queueDepth();

    /**
     * Returns true when this sink is, right now, putting chunks straight into a renderer in this same
     * process -- voxy on an integrated server. The singleplayer download client asks this: when the
     * world's own server is already feeding the renderer as it generates, downloading the same LOD from
     * that server again is pure duplication.
     */
    default boolean feedsRendererDirectly() {
        return false;
    }

    LodSink NOOP = new LodSink() {

        @Override
        public boolean offer(Object chunk) {
            return true;
        }

        @Override
        public int queueDepth() {
            return 0;
        }

        @Override
        public String toString() {
            return "LodSink.NOOP";
        }
    };
}
