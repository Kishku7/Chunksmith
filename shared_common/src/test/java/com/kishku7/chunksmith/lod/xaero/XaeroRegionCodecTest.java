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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.kishku7.chunksmith.lod.CsLodChunk;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The codec is pinned against regions World Map 1.47.2 itself wrote (a surface region and a cave-layer region,
 * captured from a real client). Re-encoding what the decoder read must reproduce them byte for byte: that holds
 * the palette order, every bit of the pixel parameters and the tile footer, so a drift in any of them fails here
 * instead of showing up as a wrong or missing map in somebody's game.
 */
public class XaeroRegionCodecTest {

    private static byte[] resource(final String name) throws IOException {
        try (InputStream in = XaeroRegionCodecTest.class.getResourceAsStream("/xaero/" + name)) {
            assertNotNull("missing test resource " + name, in);
            return in.readAllBytes();
        }
    }

    @Test
    public void reencodingARealSurfaceRegionIsByteIdentical() throws Exception {
        byte[] raw = XaeroRegionCodec.unzip(resource("wm-1.47.2-surface-m1_0.zip"));
        XaeroTile[] tiles = XaeroRegionCodec.decode(raw);
        assertArrayEquals(raw, XaeroRegionCodec.encode(tiles));
    }

    @Test
    public void reencodingARealCaveRegionIsByteIdentical() throws Exception {
        byte[] raw = XaeroRegionCodec.unzip(resource("wm-1.47.2-cave3-m1_0.zip"));
        assertArrayEquals(raw, XaeroRegionCodec.encode(XaeroRegionCodec.decode(raw)));
    }

    @Test(expected = IOException.class)
    public void aNewerFormatIsRefusedNotGuessedAt() throws Exception {
        byte[] raw = XaeroRegionCodec.unzip(resource("wm-1.47.2-surface-m1_0.zip")).clone();
        // The version int is bytes 1..4 big-endian; minor 9 is newer than this codec writes.
        raw[4] = 9;
        XaeroRegionCodec.decode(raw);
    }

    @Test
    public void existingTilesWinAndOursFillTheGaps() throws Exception {
        byte[] raw = XaeroRegionCodec.unzip(resource("wm-1.47.2-surface-m1_0.zip"));
        XaeroTile[] existing = XaeroRegionCodec.decode(raw);
        int taken = -1;
        int free = -1;
        for (int i = 0; i < existing.length; i++) {
            if (existing[i] != null && taken < 0) {
                taken = i;
            }
            if (existing[i] == null && free < 0) {
                free = i;
            }
        }
        // Region (-1, 0): tile index is lx * 32 + lz, chunk x = -32 + lx.
        List<CsLodChunk> chunks = new ArrayList<>();
        chunks.add(grassChunk(-32 + taken / 32, taken % 32));
        chunks.add(grassChunk(-32 + free / 32, free % 32));
        XaeroRegionWriter.Region merged = XaeroRegionWriter.build(-1, 0, chunks, raw);
        assertEquals("only the free tile is added", 1, merged.tilesAdded);
        XaeroTile[] after = XaeroRegionCodec.decode(merged.xaeroBytes);
        assertNotNull(after[free]);
        assertEquals("the tile World Map already had is untouched",
                existing[taken].pixels[0].state, after[taken].pixels[0].state);
        assertTrue(!merged.unchanged());
        assertTrue("a second pass adds nothing", XaeroRegionWriter.build(-1, 0, chunks, merged.xaeroBytes).unchanged());
    }

    @Test
    public void aTileWeWroteIsRefreshedWhenItsRecordChangesButNotOnceWorldMapRedrawsIt() throws Exception {
        CsLodChunk original = grassChunk(5, 7);
        XaeroRegionWriter.Region first = XaeroRegionWriter.build(0, 0, List.of(original), null, null);
        assertEquals(1, first.tilesAdded);

        // Same record again: nothing to do.
        assertTrue(XaeroRegionWriter.build(0, 0, List.of(original), first.xaeroBytes, first.owned).unchanged());

        // The record changed (a taller column): our tile, untouched since, is refreshed.
        CsLodChunk changed = tallerChunk(5, 7);
        XaeroRegionWriter.Region second = XaeroRegionWriter.build(0, 0, List.of(changed), first.xaeroBytes, first.owned);
        assertEquals(1, second.tilesAdded);
        int index = XaeroRegionCodec.tileIndex(5, 7);
        assertEquals(70, XaeroRegionCodec.decode(second.xaeroBytes)[index].pixels[0].height);

        // World Map redraws that tile (different content from what we wrote): the old ownership no longer matches,
        // so a later change to the record must not overwrite what World Map drew.
        XaeroTile[] tiles = XaeroRegionCodec.decode(second.xaeroBytes);
        XaeroTile redrawn = XaeroTileBuilder.build(grassChunk(5, 7));
        tiles[index] = redrawn;
        byte[] redrawnBytes = XaeroRegionCodec.encode(tiles);
        assertTrue(XaeroRegionWriter.build(0, 0, List.of(changed), redrawnBytes, second.owned).unchanged());
    }

    @Test
    public void aRegionWithNothingForUsIsLeftAlone() throws Exception {
        assertTrue(XaeroRegionWriter.build(0, 0, new ArrayList<>(), null).unchanged());
    }

    /** Like {@link #grassChunk} but the surface is at y=70, a stone block on top. */
    static CsLodChunk tallerChunk(final int chunkX, final int chunkZ) {
        List<String> blocks = List.of("minecraft:air", "minecraft:stone", "minecraft:grass_block[snowy=false]");
        List<CsLodChunk.Section> sections = new ArrayList<>();
        for (int s = -4; s < 20; s++) {
            int base = s * 16;
            if (base + 15 < 64) {
                sections.add(new CsLodChunk.Section(null, 1, null, 0, null, 15, null, 0));
            } else if (base > 64) {
                sections.add(new CsLodChunk.Section(null, 0, null, 0, null, 15, null, 0));
            } else {
                int[] voxels = new int[CsLodChunk.BLOCKS_PER_SECTION];
                for (int y = 0; y < 16; y++) {
                    for (int i = 0; i < 256; i++) {
                        voxels[y * 256 + i] = base + y <= 70 ? 1 : 0;
                    }
                }
                sections.add(new CsLodChunk.Section(voxels, -1, null, 0, null, 15, null, 0));
            }
        }
        return new CsLodChunk("minecraft:overworld", chunkX, chunkZ, -4, blocks, List.of("minecraft:plains"), sections);
    }

    /** A flat chunk: stone to y=59, grass at y=60, air above, plains, no light. Sections -4..19. */
    static CsLodChunk grassChunk(final int chunkX, final int chunkZ) {
        List<String> blocks = List.of("minecraft:air", "minecraft:stone", "minecraft:grass_block[snowy=false]");
        List<CsLodChunk.Section> sections = new ArrayList<>();
        for (int s = -4; s < 20; s++) {
            int base = s * 16;
            if (base + 15 < 60) {
                sections.add(new CsLodChunk.Section(null, 1, null, 0, null, 15, null, 0));
            } else if (base > 60) {
                sections.add(new CsLodChunk.Section(null, 0, null, 0, null, 15, null, 0));
            } else {
                int[] voxels = new int[CsLodChunk.BLOCKS_PER_SECTION];
                for (int y = 0; y < 16; y++) {
                    for (int i = 0; i < 256; i++) {
                        int wy = base + y;
                        voxels[y * 256 + i] = wy < 60 ? 1 : wy == 60 ? 2 : 0;
                    }
                }
                sections.add(new CsLodChunk.Section(voxels, -1, null, 0, null, 15, null, 0));
            }
        }
        return new CsLodChunk("minecraft:overworld", chunkX, chunkZ, -4, blocks, List.of("minecraft:plains"), sections);
    }
}
