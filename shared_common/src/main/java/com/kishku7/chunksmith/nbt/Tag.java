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

public abstract class Tag {
    protected static final int INDENT = 2;
    protected final String name;

    protected Tag(String name) {
        this.name = name;
    }

    public static Tag load(DataInput input) throws IOException {
        byte type = input.readByte();
        if (TagType.END == type) {
            return new EndTag();
        }
        String name = input.readUTF();
        Tag tag = create(type, name);
        tag.read(input);
        return tag;
    }

    public static byte pass(DataInput input) throws IOException {
        byte type = input.readByte();
        if (TagType.END == type) {
            return type;
        }
        int size = input.readUnsignedShort();
        input.skipBytes(size);
        create(type, "").skip(input);
        return type;
    }

    public static void save(DataOutput output, Tag tag) throws IOException {
        byte type = tag.type();
        output.writeByte(type);
        if (TagType.END == type) {
            return;
        }
        output.writeUTF(tag.name());
        tag.write(output);
    }

    public static Tag find(DataInput input, byte type, String name) throws IOException {
        byte t = input.readByte();
        if (TagType.END == t) {
            return new EndTag();
        }
        String n = input.readUTF();
        Tag tag = create(t, n);
        if (type == t && name.equals(n)) {
            tag.read(input);
            return tag;
        }
        return tag.search(input, type, name);
    }

    public static Tag create(byte type, String name) {
        return switch (type) {
            case TagType.END -> new EndTag();
            case TagType.BYTE -> new ByteTag(name);
            case TagType.SHORT -> new ShortTag(name);
            case TagType.INT -> new IntTag(name);
            case TagType.LONG -> new LongTag(name);
            case TagType.FLOAT -> new FloatTag(name);
            case TagType.DOUBLE -> new DoubleTag(name);
            case TagType.BYTE_ARRAY -> new ByteArrayTag(name);
            case TagType.STRING -> new StringTag(name);
            case TagType.LIST -> new ListTag(name);
            case TagType.COMPOUND -> new CompoundTag(name);
            case TagType.INT_ARRAY -> new IntArrayTag(name);
            case TagType.LONG_ARRAY -> new LongArrayTag(name);
            default -> throw new IllegalArgumentException("Invalid tag type %d".formatted(type));
        };
    }

    public String name() {
        return name;
    }

    abstract void read(DataInput input) throws IOException;

    abstract void skip(DataInput input) throws IOException;

    abstract void write(DataOutput output) throws IOException;

    abstract Tag search(DataInput input, byte type, String name) throws IOException;

    abstract byte type();

    abstract String typeName();

    abstract String print(int level);

    @Override
    public String toString() {
        return print(0);
    }
}
