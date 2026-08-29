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

package com.kishku7.chunksmith.iterator;

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.util.ChunkCoordinate;
import com.kishku7.chunksmith.util.Input;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class CsvChunkIterator implements ChunkIterator {
    private final Queue<ChunkCoordinate> chunks;
    private final long total;
    private final String name;

    public CsvChunkIterator(Selection selection, long count) {
        this(selection);
        for (int i = 0; i < count && hasNext(); ++i) {
            chunks.poll();
        }
    }

    public CsvChunkIterator(Selection selection) {
        Path filePath = selection.pattern().getValue()
                .map(value -> selection.chunky().getConfig().getDirectory().resolve(String.format("%s.csv", value)))
                .orElse(null);
        this.chunks = new LinkedList<>();
        AtomicLong valid = new AtomicLong();
        if (filePath != null) {
            try (final Stream<String> lines = Files.lines(filePath)) {
                lines.forEach(line -> {
                    String[] split = line.split(",");
                    if (split.length > 1) {
                        Optional<Integer> x = Input.tryInteger(split[0]);
                        Optional<Integer> z = Input.tryInteger(split[1]);
                        if (x.isPresent() && z.isPresent()) {
                            chunks.add(new ChunkCoordinate(x.get(), z.get()));
                            valid.incrementAndGet();
                        }
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        this.total = valid.get();
        this.name = selection.pattern().toString();
    }

    @Override
    public boolean hasNext() {
        return !chunks.isEmpty();
    }

    @Override
    public ChunkCoordinate next() {
        if (chunks.isEmpty()) {
            throw new NoSuchElementException();
        }
        return chunks.poll();
    }

    @Override
    public long total() {
        return total;
    }

    @Override
    public String name() {
        return name;
    }
}
