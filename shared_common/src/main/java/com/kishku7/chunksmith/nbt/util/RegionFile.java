/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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

package com.kishku7.chunksmith.nbt.util;

import com.kishku7.chunksmith.nbt.Tag;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.InflaterInputStream;

public final class RegionFile {
    private static final int ENTRIES = 1024;
    private static final int SECTOR_SIZE = 4096;
    private final Set<Chunk> chunks = new HashSet<>();
    private final Map<ChunkPos, Chunk> chunkMap = new HashMap<>();

    public RegionFile(File file) {
        this(file, null);
    }

    public RegionFile(File file, ChunkFilter filter) {
        try (final RandomAccessFile region = new RandomAccessFile(file, "r")) {
            if (region.length() < 4096) {
                return;
            }
            String regionFileName = file.getName();
            if (!regionFileName.startsWith("r.")) {
                return;
            }
            int extension = regionFileName.indexOf(".mca");
            if (extension < 2) {
                return;
            }
            String regionCoordinates = regionFileName.substring(2, extension);
            int separator = regionCoordinates.indexOf('.');
            int regionX;
            int regionZ;
            try {
                regionX = Integer.parseInt(regionCoordinates.substring(0, separator));
                regionZ = Integer.parseInt(regionCoordinates.substring(separator + 1));
            } catch (NumberFormatException e) {
                return;
            }
            int[] offsetTable = new int[ENTRIES];
            int[] sizeTable = new int[ENTRIES];
            for (int i = 0; i < ENTRIES; ++i) {
                int location = region.readInt();
                offsetTable[i] = (location >> 8) & 0xFFFFFF;
                sizeTable[i] = location & 0xFF;
            }
            int[] timestampTable = new int[ENTRIES];
            for (int i = 0; i < ENTRIES; ++i) {
                timestampTable[i] = region.readInt();
            }
            for (int i = 0; i < ENTRIES; ++i) {
                int offset = offsetTable[i] * SECTOR_SIZE;
                int size = sizeTable[i] * SECTOR_SIZE;
                if (offset == 0 && size == 0) {
                    continue;
                }
                region.seek(offset);
                int length = region.readInt();
                byte compressionType = region.readByte();
                if (compressionType != 2) {
                    throw new UnsupportedOperationException("Not in zlib format");
                }
                byte[] compressed = new byte[length - 1];
                region.readFully(compressed);
                try (final ByteArrayInputStream bytes = new ByteArrayInputStream(compressed);
                     InflaterInputStream inflater = new InflaterInputStream(bytes);
                     BufferedInputStream buffer = new BufferedInputStream(inflater);
                     DataInputStream input = new DataInputStream(buffer)) {
                    int x = (regionX * 32) + (i % 32);
                    int z = (regionZ * 32) + (i / 32);
                    Tag data;
                    if (filter == null) {
                        data = Tag.load(input);
                    } else {
                        data = Tag.find(input, filter.getType(), filter.getName());
                    }
                    Chunk chunk = new Chunk(x, z, data, timestampTable[i]);
                    chunks.add(chunk);
                    chunkMap.put(ChunkPos.of(x, z), chunk);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Collection<Chunk> getChunks() {
        return chunks;
    }

    public Optional<Chunk> getChunk(int x, int z) {
        return Optional.ofNullable(chunkMap.get(ChunkPos.of(x, z)));
    }
}
