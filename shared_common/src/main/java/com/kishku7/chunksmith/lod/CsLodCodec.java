package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.lod.net.CsLodProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class CsLodCodec {

    public static final int MAGIC = 0x43534C44;

    public static final int VERSION = 1;

    private static final int FLAG_UNIFORM_BLOCK = 1;
    private static final int FLAG_UNIFORM_BIOME = 1 << 1;
    private static final int FLAG_UNIFORM_SKY = 1 << 2;
    private static final int FLAG_UNIFORM_BLOCK_LIGHT = 1 << 3;

    private CsLodCodec() {
    }

    public static byte[] encode(CsLodChunk chunk) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream(8192);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(raw))) {
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeUTF(chunk.getDimension());
            out.writeInt(chunk.getChunkX());
            out.writeInt(chunk.getChunkZ());
            out.writeInt(chunk.getMinSectionY());
            out.writeByte(chunk.getSections().size());

            writePalette(out, chunk.getBlockPalette());
            writePalette(out, chunk.getBiomePalette());

            final int blockWidth = indexWidth(chunk.getBlockPalette().size());
            final int biomeWidth = indexWidth(chunk.getBiomePalette().size());

            for (CsLodChunk.Section section : chunk.getSections()) {
                int flags = 0;
                if (section.getUniformBlock() >= 0) {
                    flags |= FLAG_UNIFORM_BLOCK;
                }
                if (section.getUniformBiome() >= 0) {
                    flags |= FLAG_UNIFORM_BIOME;
                }
                if (section.getUniformSky() >= 0) {
                    flags |= FLAG_UNIFORM_SKY;
                }
                if (section.getUniformBlockLight() >= 0) {
                    flags |= FLAG_UNIFORM_BLOCK_LIGHT;
                }
                out.writeByte(flags);

                if (section.getUniformBlock() >= 0) {
                    writeVarInt(out, section.getUniformBlock());
                } else {
                    writeIndices(out, section.getBlocks(), blockWidth);
                }
                if (section.getUniformBiome() >= 0) {
                    writeVarInt(out, section.getUniformBiome());
                } else {
                    writeIndices(out, section.getBiomes(), biomeWidth);
                }
                if (section.getUniformSky() >= 0) {
                    out.writeByte(section.getUniformSky());
                } else {
                    out.write(section.getSkyLight());
                }
                if (section.getUniformBlockLight() >= 0) {
                    out.writeByte(section.getUniformBlockLight());
                } else {
                    out.write(section.getBlockLight());
                }
            }
        }
        return raw.toByteArray();
    }

    public static CsLodChunk decode(byte[] compressed) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
            final int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Not a CSLOD record (magic " + Integer.toHexString(magic) + ")");
            }
            final int version = in.readUnsignedShort();
            if (version != VERSION) {
                throw new IOException("Unsupported CSLOD version " + version + " (this build reads " + VERSION + ")");
            }
            final String dimension = in.readUTF();
            final int chunkX = in.readInt();
            final int chunkZ = in.readInt();
            final int minSectionY = in.readInt();
            final int sectionCount = in.readUnsignedByte();
            if (sectionCount > CsLodProtocol.MAX_SECTIONS) {
                throw new IOException("CSLOD record: section count " + sectionCount + " exceeds "
                        + CsLodProtocol.MAX_SECTIONS);
            }

            final List<String> blockPalette = readPalette(in);
            final List<String> biomePalette = readPalette(in);

            final int blockWidth = indexWidth(blockPalette.size());
            final int biomeWidth = indexWidth(biomePalette.size());

            final List<CsLodChunk.Section> sections = new ArrayList<>(sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                final int flags = in.readUnsignedByte();

                int uniformBlock = -1;
                int[] blocks = null;
                if ((flags & FLAG_UNIFORM_BLOCK) != 0) {
                    uniformBlock = readVarInt(in);
                } else {
                    blocks = readIndices(in, CsLodChunk.BLOCKS_PER_SECTION, blockWidth);
                }

                int uniformBiome = -1;
                int[] biomes = null;
                if ((flags & FLAG_UNIFORM_BIOME) != 0) {
                    uniformBiome = readVarInt(in);
                } else {
                    biomes = readIndices(in, CsLodChunk.BIOMES_PER_SECTION, biomeWidth);
                }

                int uniformSky = -1;
                byte[] skyLight = null;
                if ((flags & FLAG_UNIFORM_SKY) != 0) {
                    uniformSky = in.readUnsignedByte();
                } else {
                    skyLight = new byte[CsLodChunk.LIGHT_BYTES];
                    in.readFully(skyLight);
                }

                int uniformBlockLight = -1;
                byte[] blockLight = null;
                if ((flags & FLAG_UNIFORM_BLOCK_LIGHT) != 0) {
                    uniformBlockLight = in.readUnsignedByte();
                } else {
                    blockLight = new byte[CsLodChunk.LIGHT_BYTES];
                    in.readFully(blockLight);
                }

                sections.add(new CsLodChunk.Section(blocks, uniformBlock, biomes, uniformBiome,
                        skyLight, uniformSky, blockLight, uniformBlockLight));
            }
            return new CsLodChunk(dimension, chunkX, chunkZ, minSectionY, blockPalette, biomePalette, sections);
        }
    }

    private static int indexWidth(int paletteSize) {
        return paletteSize <= 256 ? 1 : 2;
    }

    private static void writeIndices(DataOutputStream out, int[] indices, int width)
            throws IOException {
        if (width == 1) {
            for (int index : indices) {
                out.writeByte(index);
            }
        } else {
            for (int index : indices) {
                out.writeShort(index);
            }
        }
    }

    private static int[] readIndices(DataInputStream in, int count, int width)
            throws IOException {
        final int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = width == 1 ? in.readUnsignedByte() : in.readUnsignedShort();
        }
        return indices;
    }

    private static void writePalette(DataOutputStream out, List<String> palette) throws IOException {
        writeVarInt(out, palette.size());
        for (String entry : palette) {
            out.writeUTF(entry);
        }
    }

    private static List<String> readPalette(DataInputStream in) throws IOException {
        final int size = readVarInt(in);
        // Bound before allocating: size is off the wire/disk. At most 65536 entries are ever addressable
        // (indices are 1 or 2 bytes wide), so a larger count is malformed, not merely large.
        if (size < 0 || size > CsLodProtocol.MAX_PALETTE_SIZE) {
            throw new IOException("CSLOD record: palette size " + size + " out of range [0, "
                    + CsLodProtocol.MAX_PALETTE_SIZE + "]");
        }
        // Do not presize from the count: each entry is a further readUTF that hits EOF if the record is
        // short, so a lie is caught without pre-allocating.
        final List<String> palette = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            palette.add(in.readUTF());
        }
        return palette;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int remaining = value;
        while ((remaining & 0xFFFFFF80) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining & 0x7F);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            final int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 35) {
                throw new IOException("VarInt too long");
            }
        }
    }
}
