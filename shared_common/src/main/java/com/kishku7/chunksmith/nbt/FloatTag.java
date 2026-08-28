package com.kishku7.chunksmith.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class FloatTag extends Tag {
    private float value;

    protected FloatTag(String name) {
        super(name);
    }

    public FloatTag(String name, float value) {
        super(name);
        this.value = value;
    }

    @Override
    public void read(DataInput input) throws IOException {
        this.value = input.readFloat();
    }

    @Override
    public void skip(DataInput input) throws IOException {
        input.skipBytes(4);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeFloat(value);
    }

    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        skip(input);
        return null;
    }

    @Override
    public byte type() {
        return TagType.FLOAT;
    }

    @Override
    public String typeName() {
        return "TAG_Float";
    }

    @Override
    public String print(int level) {
        return "%s%s('%s'): %f".formatted(" ".repeat(level * Tag.INDENT), typeName(), name, value);
    }

    public float value() {
        return value;
    }

    public void value(float value) {
        this.value = value;
    }
}
