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
        final int size = input.readInt();
        this.value = new int[size];
        for (int i = 0; i < size; ++i) {
            value[i] = input.readInt();
        }
    }

    @Override
    public void skip(DataInput input) throws IOException {
        final int size = input.readInt();
        input.skipBytes(4 * size);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        final int size = value.length;
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
