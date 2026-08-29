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
import java.util.Arrays;

public class IntArrayTag extends Tag {
    private int[] value;

    protected IntArrayTag(String name) {
        super(name);
    }

    public IntArrayTag(String name, int[] value) {
        super(name);
        this.value = value;
    }

    @Override
    public void read(DataInput input) throws IOException {
        int size = input.readInt();
        this.value = new int[size];
        for (int i = 0; i < size; ++i) {
            value[i] = input.readInt();
        }
    }

    @Override
    public void skip(DataInput input) throws IOException {
        int size = input.readInt();
        input.skipBytes(4 * size);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        int size = value.length;
        output.writeInt(size);
        for (int i : value) {
            output.writeInt(i);
        }
    }

    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        skip(input);
        return null;
    }

    @Override
    public byte type() {
        return TagType.INT_ARRAY;
    }

    @Override
    public String typeName() {
        return "TAG_Int_Array";
    }

    @Override
    public String print(int level) {
        return "%s%s('%s'): %s".formatted(" ".repeat(level * Tag.INDENT), typeName(), name, Arrays.toString(value));
    }

    public int[] value() {
        return value;
    }

    public void value(int[] value) {
        this.value = value;
    }
}
