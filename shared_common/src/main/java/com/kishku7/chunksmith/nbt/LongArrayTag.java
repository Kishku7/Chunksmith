package com.kishku7.chunksmith.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

public class LongArrayTag extends Tag {
    private long[] value;

    protected LongArrayTag(String name) {
        super(name);
    }

    public LongArrayTag(String name, long[] value) {
        super(name);
        this.value = value;
    }

    @Override
    public void read(DataInput input) throws IOException {
        int size = input.readInt();
        this.value = new long[size];
        for (int i = 0; i < size; ++i) {
            value[i] = input.readLong();
        }
    }

    @Override
    public void skip(DataInput input) throws IOException {
        int size = input.readInt();
        input.skipBytes(8 * size);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        int size = value.length;
        output.writeLong(size);
        for (long i : value) {
            output.writeLong(i);
        }
    }

    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        skip(input);
        return null;
    }

    @Override
    public byte type() {
        return TagType.LONG_ARRAY;
    }

    @Override
    public String typeName() {
        return "TAG_Long_Array";
    }

    @Override
    public String print(int level) {
        return "%s%s('%s'): %s".formatted(" ".repeat(level * Tag.INDENT), typeName(), name, Arrays.toString(value));
    }

    public long[] value() {
        return value;
    }

    public void value(long[] value) {
        this.value = value;
    }
}
