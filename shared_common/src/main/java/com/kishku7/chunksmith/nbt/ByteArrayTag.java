package com.kishku7.chunksmith.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

public class ByteArrayTag extends Tag {
    private byte[] value;

    protected ByteArrayTag(String name) {
        super(name);
    }

    public ByteArrayTag(String name, byte[] value) {
        super(name);
        this.value = value;
    }

    @Override
    public void read(DataInput input) throws IOException {
        int size = input.readInt();
        this.value = new byte[size];
        input.readFully(value);
    }

    @Override
    public void skip(DataInput input) throws IOException {
        int size = input.readInt();
        input.skipBytes(size);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        skip(input);
        return null;
    }

    @Override
    public byte type() {
        return TagType.BYTE_ARRAY;
    }

    @Override
    public String typeName() {
        return "TAG_Byte_Array";
    }

    @Override
    public String print(int level) {
        return "%s%s('%s'): %s".formatted(" ".repeat(level * Tag.INDENT), typeName(), name, Arrays.toString(value));
    }

    public byte[] value() {
        return value;
    }

    public void value(byte[] value) {
        this.value = value;
    }
}
