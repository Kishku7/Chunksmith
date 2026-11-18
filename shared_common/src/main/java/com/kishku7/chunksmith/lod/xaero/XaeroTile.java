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

package com.kishku7.chunksmith.lod.xaero;

import java.util.List;

/**
 * One 16x16 map tile (one chunk) of a Xaero World Map region, in the form the region file stores it: a pixel per
 * block column, indexed {@code x * 16 + z}. See {@link XaeroRegionCodec} for the byte layout.
 */
public final class XaeroTile {

    public static final int SIZE = 16;

    public final XaeroPixel[] pixels = new XaeroPixel[SIZE * SIZE];
    public int worldInterpretationVersion = XaeroRegionCodec.WORLD_INTERPRETATION_VERSION;
    public int caveStart = XaeroRegionCodec.SURFACE_CAVE_START;
    public int caveDepth = XaeroRegionCodec.DEFAULT_CAVE_DEPTH;

    /**
     * A hash of everything the region file stores for this tile. Two tiles with the same content hash encode to the
     * same bytes, which is how a tile Chunksmith wrote is told from one World Map has since redrawn. Never zero.
     */
    public int contentHash() {
        int h = 17;
        for (XaeroPixel p : pixels) {
            h = 31 * h + p.state.hashCode();
            h = 31 * h + p.height;
            h = 31 * h + p.topHeight;
            h = 31 * h + p.light;
            h = 31 * h + (p.biome == null ? 0 : p.biome.hashCode());
            for (Overlay o : p.overlays) {
                h = 31 * h + o.state.hashCode();
                h = 31 * h + o.opacity * 16 + o.light;
            }
            h = 31 * h + p.overlays.size();
        }
        h = 31 * h + worldInterpretationVersion;
        h = 31 * h + caveStart;
        h = 31 * h + caveDepth;
        return h == 0 ? 1 : h;
    }

    /** One map pixel: the block the player sees from above, plus any translucent layers over it. */
    public static final class XaeroPixel {
        /** Block-state string, {@code id[prop=value,...]}. Xaero treats any grass_block as plain grass. */
        public final String state;
        public final int height;
        public final int topHeight;
        public final int light;
        /** Biome id, or null when the pixel carries none. */
        public final String biome;
        public final List<Overlay> overlays;

        public XaeroPixel(final String state, final int height, final int topHeight, final int light,
                          final String biome, final List<Overlay> overlays) {
            // The file keeps no state for grass (every grass_block is the plain one), so normalise here: a tile read
            // back from a file must equal, and hash equal to, the tile that was written.
            this.state = "minecraft:grass_block".equals(XaeroRegionCodec.idOf(state)) ? "minecraft:grass_block" : state;
            this.height = height;
            this.topHeight = topHeight;
            this.light = light;
            this.biome = biome;
            this.overlays = overlays;
        }

        public boolean isGrass() {
            return "minecraft:grass_block".equals(XaeroRegionCodec.idOf(state));
        }
    }

    /** A translucent layer (water, stained glass, ice...) drawn over a pixel's block. */
    public static final class Overlay {
        /** {@code minecraft:water} is stored with no state; anything else carries its state string. */
        public final String state;
        public final int opacity;
        public final int light;

        public Overlay(final String state, final int opacity, final int light) {
            this.state = state;
            this.opacity = opacity;
            this.light = light;
        }

        public boolean isWater() {
            return "minecraft:water".equals(XaeroRegionCodec.idOf(state));
        }
    }
}
