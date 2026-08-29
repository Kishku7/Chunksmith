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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CompoundTag extends Tag {
    private Map<String, Tag> value = new HashMap<>();

    protected CompoundTag(String name) {
        super(name);
    }

    public CompoundTag(String name, Map<String, Tag> value) {
        super(name);
        this.value = value;
    }

    @Override
    public void read(DataInput input) throws IOException {
        this.value = new HashMap<>();
        Tag tag;
        while (TagType.END != (tag = Tag.load(input)).type()) {
            this.value.put(tag.name(), tag);
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void skip(DataInput input) throws IOException {
        while (TagType.END != Tag.pass(input)) ;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        for (Tag tag : value.values()) {
            Tag.save(output, tag);
        }
        output.writeByte(TagType.END);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        Tag tag;
        while ((tag = Tag.find(input, type, name)) == null) ;
        if (TagType.END == tag.type()) {
            return null;
        }
        return tag;
    }

    @Override
    public byte type() {
        return TagType.COMPOUND;
    }

    @Override
    public String typeName() {
        return "TAG_Compound";
    }

    @Override
    public String print(int level) {
        final int size = value.size();
        final String entry = size == 1 ? "entry" : "entries";
        final String indent = " ".repeat(level * Tag.INDENT);
        final StringBuilder compoundBuilder = new StringBuilder("%s%s('%s'): %d %s".formatted(" ".repeat(level * Tag.INDENT), typeName(), name, size, entry));
        compoundBuilder.append('\n').append(indent).append("{\n");
        for (Tag tag : value.values()) {
            compoundBuilder.append(tag.print(level + 1)).append('\n');
        }
        compoundBuilder.append(indent).append('}');
        return compoundBuilder.toString();
    }

    public Optional<Tag> get(String name) {
        return Optional.ofNullable(value.get(name));
    }

    public Optional<ByteArrayTag> getByteArray(String name) {
        return get(name).filter(ByteArrayTag.class::isInstance).flatMap(tag -> Optional.of((ByteArrayTag) tag));
    }

    public Optional<ByteTag> getByte(String name) {
        return get(name).filter(ByteTag.class::isInstance).flatMap(tag -> Optional.of((ByteTag) tag));
    }

    public Optional<CompoundTag> getCompound(String name) {
        return get(name).filter(CompoundTag.class::isInstance).flatMap(tag -> Optional.of((CompoundTag) tag));
    }

    public Optional<DoubleTag> getDouble(String name) {
        return get(name).filter(DoubleTag.class::isInstance).flatMap(tag -> Optional.of((DoubleTag) tag));
    }

    public Optional<FloatTag> getFloat(String name) {
        return get(name).filter(FloatTag.class::isInstance).flatMap(tag -> Optional.of((FloatTag) tag));
    }

    public Optional<IntArrayTag> getIntArray(String name) {
        return get(name).filter(IntArrayTag.class::isInstance).flatMap(tag -> Optional.of((IntArrayTag) tag));
    }

    public Optional<IntTag> getInt(String name) {
        return get(name).filter(IntTag.class::isInstance).flatMap(tag -> Optional.of((IntTag) tag));
    }

    public Optional<ListTag> getList(String name) {
        return get(name).filter(ListTag.class::isInstance).flatMap(tag -> Optional.of((ListTag) tag));
    }

    public Optional<LongArrayTag> getLongArray(String name) {
        return get(name).filter(LongArrayTag.class::isInstance).flatMap(tag -> Optional.of((LongArrayTag) tag));
    }

    public Optional<LongTag> getLong(String name) {
        return get(name).filter(LongTag.class::isInstance).flatMap(tag -> Optional.of((LongTag) tag));
    }

    public Optional<ShortTag> getShort(String name) {
        return get(name).filter(ShortTag.class::isInstance).flatMap(tag -> Optional.of((ShortTag) tag));
    }

    public Optional<StringTag> getString(String name) {
        return get(name).filter(StringTag.class::isInstance).flatMap(tag -> Optional.of((StringTag) tag));
    }

    public void put(Tag tag) {
        value.put(tag.name(), tag);
    }

    public void remove(String name) {
        value.remove(name);
    }
}
