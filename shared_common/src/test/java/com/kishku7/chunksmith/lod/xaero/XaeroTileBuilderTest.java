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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.kishku7.chunksmith.lod.CsLodChunk;
import com.kishku7.chunksmith.lod.xaero.XaeroTile.XaeroPixel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The surface rules of {@link XaeroTileBuilder}: what a map pixel is made of, for the cases that differ. */
public class XaeroTileBuilderTest {

    private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:stone", "minecraft:sand",
            "minecraft:water[level=0]", "minecraft:short_grass", "minecraft:grass_block[snowy=false]",
            "minecraft:red_stained_glass", "minecraft:kelp[age=3]");

    /**
     * The pixel of the column at x=0, z=0 whose block at world y = i is palette index {@code blockAtY[i]}.
     * Below y=0 is stone and above the array is air; sections -4..19.
     */
    private static XaeroPixel pixel(final int... blockAtY) {
        List<CsLodChunk.Section> sections = new ArrayList<>();
        for (int s = -4; s < 20; s++) {
            int[] voxels = new int[CsLodChunk.BLOCKS_PER_SECTION];
            for (int y = 0; y < 16; y++) {
                int wy = s * 16 + y;
                if (wy >= 0 && wy < blockAtY.length) {
                    voxels[y * 256] = blockAtY[wy];
                } else if (wy < 0) {
                    voxels[y * 256] = 1;
                }
            }
            sections.add(new CsLodChunk.Section(voxels, -1, null, 0, null, 15, null, 0));
        }
        CsLodChunk chunk = new CsLodChunk("minecraft:overworld", 0, 0, -4, PALETTE, List.of("minecraft:plains"), sections);
        return XaeroTileBuilder.build(chunk).pixels[0];
    }

    @Test
    public void topOpaqueBlockWinsAndIsGrassWhenGrass() {
        XaeroPixel p = pixel(1, 1, 5);
        assertEquals(2, p.height);
        assertEquals(2, p.topHeight);
        assertTrue(p.isGrass());
        assertTrue(p.overlays.isEmpty());
    }

    @Test
    public void hiddenPlantsAreSeenThrough() {
        XaeroPixel p = pixel(1, 1, 5, 4);
        assertEquals("the grass below short_grass", 2, p.height);
        assertEquals("the plant raised nothing", 2, p.topHeight);
    }

    @Test
    public void waterIsAnOverlayWithOpacityPerBlockAndTheBedBelow() {
        XaeroPixel p = pixel(1, 2, 3, 3, 3);
        assertEquals(1, p.height);
        assertEquals("minecraft:sand", p.state);
        assertEquals(4, p.topHeight);
        assertEquals(1, p.overlays.size());
        assertTrue(p.overlays.get(0).isWater());
        assertEquals(3, p.overlays.get(0).opacity);
    }

    @Test
    public void glassAboveWaterIsAnotherLayer() {
        XaeroPixel p = pixel(1, 2, 3, 6);
        assertEquals(2, p.overlays.size());
        assertEquals("minecraft:red_stained_glass", p.overlays.get(0).state);
        assertTrue(p.overlays.get(1).isWater());
    }

    @Test
    public void kelpIsWaterAndAPlant() {
        XaeroPixel p = pixel(1, 2, 7, 3);
        assertTrue("water above the kelp", p.overlays.get(0).isWater());
        assertEquals("kelp is what the water covers", "minecraft:kelp[age=3]", p.state);
    }

    @Test
    public void aColumnOfOnlyStoneBelowZeroSurfacesAtTheTopOfIt() {
        XaeroPixel p = pixel();
        assertEquals("minecraft:stone", p.state);
        assertEquals(-1, p.height);
    }
}
