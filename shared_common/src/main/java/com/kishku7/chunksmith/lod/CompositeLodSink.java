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

import java.util.List;

public final class CompositeLodSink implements LodSink {

    private final List<LodSink> sinks;

    public CompositeLodSink(List<LodSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    public List<LodSink> getSinks() {
        return sinks;
    }

    @Override
    public boolean offer(Object chunk) {
        boolean accepted = true;
        for (LodSink sink : sinks) {
            if (!sink.offer(chunk)) {
                accepted = false;
            }
        }
        return accepted;
    }

    @Override
    public int queueDepth() {
        int depth = 0;
        for (LodSink sink : sinks) {
            depth = Math.max(depth, sink.queueDepth());
        }
        return depth;
    }

    @Override
    public String toString() {
        return "CompositeLodSink" + sinks;
    }
}
