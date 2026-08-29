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

package com.kishku7.chunksmith.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class LongTag extends Tag {
    private long value;

    protected LongTag(String name) {
        super(name);
    }

    public LongTag(String name, long value) {
        super(name);
        this.value = value;
    }

    @Override
    public void read(DataInput input) throws IOException {
        this.value = input.readLong();
    }

    @Override
    public void skip(DataInput input) throws IOException {
        input.skipBytes(8);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeLong(value);
    }

    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        skip(input);
        return null;
    }

    @Override
    public byte type() {
        return TagType.LONG;
    }

    @Override
    public String typeName() {
        return "TAG_Long";
    }

    @Override
    public String print(int level) {
        return "%s%s('%s'): %d".formatted(" ".repeat(level * Tag.INDENT), typeName(), name, value);
    }

    public long value() {
        return value;
    }

    public void value(long value) {
        this.value = value;
    }
}
