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
