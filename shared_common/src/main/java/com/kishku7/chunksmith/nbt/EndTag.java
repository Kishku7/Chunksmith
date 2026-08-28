package com.kishku7.chunksmith.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class EndTag extends Tag {
    public EndTag() {
        super("");
    }

    @Override
    public void read(DataInput input) throws IOException {
        // No data
    }

    @Override
    public void skip(DataInput input) throws IOException {
        // No data
    }

    @Override
    public void write(DataOutput output) throws IOException {
        // No data
    }

    @Override
    public Tag search(DataInput input, byte type, String name) throws IOException {
        skip(input);
        return null;
    }

    @Override
    public byte type() {
        return TagType.END;
    }

    @Override
    public String typeName() {
        return "TAG_End";
    }

    @Override
    public String print(int level) {
        return "";
    }
}
