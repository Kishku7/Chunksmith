package com.kishku7.chunksmith.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class ShortTag extends Tag {
    private short value;

    protected ShortTag(String name) {
        super(name);
    }

    public ShortTag(String name, short value) {
        super(name);
        this.value = value;
    }

    @Override
    public void read(DataInput input) throws IOException {
        this.value = input.readShort();
    }

    @Override
    public void skip(DataInput input) throws IOException {
        input.skipBytes(2);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeShort(value);
    }

    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        skip(input);
        return null;
    }

    @Override
    public byte type() {
        return TagType.SHORT;
    }

    @Override
    public String typeName() {
        return "TAG_Short";
    }

    @Override
    public String print(int level) {
        return "%s%s('%s'): %d".formatted(" ".repeat(level * Tag.INDENT), typeName(), name, value);
    }

    public short value() {
        return value;
    }

    public void value(short value) {
        this.value = value;
    }
}
