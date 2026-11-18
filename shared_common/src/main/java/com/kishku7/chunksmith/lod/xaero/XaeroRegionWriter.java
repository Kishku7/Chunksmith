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

import com.kishku7.chunksmith.lod.CsLodChunk;
import java.io.IOException;

/**
 * Builds the Xaero's World Map region file for one CSLOD region, merging with a region file World Map already
 * wrote. World Map's own tiles, made from chunks the client really had loaded, always win; ours only fill the
 * tiles it does not have, which is the far ring a LOD horizon exists to cover.
 */
public final class XaeroRegionWriter {

    private XaeroRegionWriter() {
    }

    /** The merged region bytes and what went into them. */
    public static final class Region {
        public final byte[] xaeroBytes;
        /** Tiles taken from CSLOD records: new ones, plus ones of ours refreshed because their record changed. */
        public final int tilesAdded;
        /** Tiles that were already in the existing file and were kept as they were. */
        public final int tilesKept;

        /**
         * Content hash of each tile this writer put in the file (0 = not ours), indexed like the region's tile
         * array. Kept beside the file so a later pass can tell a tile it wrote from one World Map has redrawn.
         */
        public final int[] owned;

        Region(final byte[] xaeroBytes, final int tilesAdded, final int tilesKept, final int[] owned) {
            this.xaeroBytes = xaeroBytes;
            this.tilesAdded = tilesAdded;
            this.tilesKept = tilesKept;
            this.owned = owned;
        }

        /** True when nothing was added or refreshed, so the existing file need not be touched. */
        public boolean unchanged() {
            return tilesAdded == 0;
        }
    }

    /**
     * Builds the region at ({@code regionX}, {@code regionZ}) from {@code chunks}, all of which should lie in it
     * (others are ignored). {@code existingRegionBytes} is the current {@code region.xaero} content, or null.
     *
     * @throws IOException when the existing bytes are not a region this codec reproduces exactly; the caller must
     *                     then leave the file alone
     */
    public static Region build(final int regionX, final int regionZ, final Iterable<CsLodChunk> chunks,
                               final byte[] existingRegionBytes) throws IOException {
        return build(regionX, regionZ, chunks, existingRegionBytes, null);
    }

    /**
     * As above, and a tile we wrote earlier is refreshed when its CSLOD record has changed, provided World Map has
     * not redrawn it since. {@code ownedBefore} is {@link Region#owned} from the previous pass, or null.
     */
    public static Region build(final int regionX, final int regionZ, final Iterable<CsLodChunk> chunks,
                               final byte[] existingRegionBytes, final int[] ownedBefore) throws IOException {
        XaeroTile[] tiles = existingRegionBytes == null
                ? new XaeroTile[XaeroRegionCodec.TILES * XaeroRegionCodec.TILES]
                : XaeroRegionCodec.decode(existingRegionBytes);
        int[] owned = new int[XaeroRegionCodec.TILES * XaeroRegionCodec.TILES];
        int kept = 0;
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != null) {
                kept++;
                // Still ours only if its content is exactly what we wrote; a tile World Map has redrawn differs.
                if (ownedBefore != null && ownedBefore[i] != 0 && tiles[i].contentHash() == ownedBefore[i]) {
                    owned[i] = ownedBefore[i];
                }
            }
        }
        int added = 0;
        for (CsLodChunk chunk : chunks) {
            int lx = chunk.getChunkX() - regionX * XaeroRegionCodec.TILES;
            int lz = chunk.getChunkZ() - regionZ * XaeroRegionCodec.TILES;
            if (lx < 0 || lx >= XaeroRegionCodec.TILES || lz < 0 || lz >= XaeroRegionCodec.TILES) {
                continue;
            }
            int index = XaeroRegionCodec.tileIndex(lx, lz);
            if (tiles[index] != null && owned[index] == 0) {
                continue;
            }
            XaeroTile fresh = XaeroTileBuilder.build(chunk);
            int hash = fresh.contentHash();
            if (tiles[index] != null && hash == owned[index]) {
                continue;
            }
            if (tiles[index] != null) {
                kept--;
            }
            tiles[index] = fresh;
            owned[index] = hash;
            added++;
        }
        return new Region(XaeroRegionCodec.encode(tiles), added, kept, owned);
    }

    /** Convenience for callers with no existing file. */
    public static Region build(final int regionX, final int regionZ, final Iterable<CsLodChunk> chunks) throws IOException {
        return build(regionX, regionZ, chunks, null);
    }

    public static byte[] zip(final byte[] xaeroBytes) throws IOException {
        return XaeroRegionCodec.zip(xaeroBytes);
    }
}
