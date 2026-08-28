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
