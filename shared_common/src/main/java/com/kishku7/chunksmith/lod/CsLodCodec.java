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

/**
 * Serializer for {@link CsLodChunk} -- the CSLOD v1 wire/disk format.
 *
 * <p>The SAME bytes are the disk record, the network payload, and the answer we hand a consumer.
 * One format, three uses.
 *
 * <p>Compression is JDK {@link java.util.zip.Deflater} on purpose: zero native dependencies. (Voxy's
 * RocksDB+ZSTD is voxy's business; we never touch it, and our store must be readable by a second
 * process -- e.g. a backfill tool -- without fighting anyone's lock.)
 *
 * <p>Record layout (all integers big-endian; "varint" = LEB128-style, 7 bits per byte):
 * <pre>
 *   magic          4 bytes  "CSLD"
 *   version        u16      = 1
 *   dimension      UTF      e.g. "minecraft:overworld"
 *   chunkX         i32
 *   chunkZ         i32
 *   minSectionY    i32      absolute (level min build height / 16)
 *   sectionCount   u8
 *   blockPalette   varint count, then UTF each   (FULL block state strings)
 *   biomePalette   varint count, then UTF each
 *   sections[sectionCount]:
 *       flags      u8   bit0 UNIFORM_BLOCK  bit1 UNIFORM_BIOME  bit2 UNIFORM_SKY  bit3 UNIFORM_BLOCKLIGHT
 *       blocks     varint (uniform) | 4096 indices, 1 byte each if palette &lt;= 256 else 2 bytes
 *       biomes     varint (uniform) | 64 indices, same width rule
 *       skyLight   u8 (uniform nibble) | 2048 packed bytes
 *       blockLight u8 (uniform nibble) | 2048 packed bytes
 * </pre>
 * The whole record is then Deflate-compressed. Uniform sections (everything above the terrain:
 * air with uniform sky light) collapse to a handful of bytes, which is what makes carrying light to
 * the build ceiling -- a hard Distant Horizons requirement -- affordable.
 *
 * <p>Every count read during {@link #decode} is validated against the ceilings in {@link CsLodProtocol}
 * BEFORE any collection or array is sized, so a hostile or corrupt record cannot OOM the reader on a
 * bogus palette or section count (the same bytes may have arrived over the wire in-band).
 */
public final class CsLodCodec {

    /** Magic bytes at the head of every record: "CSLD". */
    public static final int MAGIC = 0x43534C44;

    /** Format version. Bump on any layout change; three consumers read this. */
    public static final int VERSION = 1;

    private static final int FLAG_UNIFORM_BLOCK = 1;
    private static final int FLAG_UNIFORM_BIOME = 1 << 1;
    private static final int FLAG_UNIFORM_SKY = 1 << 2;
    private static final int FLAG_UNIFORM_BLOCK_LIGHT = 1 << 3;

    private CsLodCodec() {
    }

    /** Encode + Deflate a chunk record. */
    public static byte[] encode(final CsLodChunk chunk) throws IOException {
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

            for (final CsLodChunk.Section section : chunk.getSections()) {
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

    /** Inflate + decode a chunk record produced by {@link #encode}. */
    public static CsLodChunk decode(final byte[] compressed) throws IOException {
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
            // sectionCount rides a u8 so it is inherently bounded to 255; the check documents the ceiling
            // and guards a future width change. Validate BEFORE sizing the section list.
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

    /** 1 byte per index while the palette fits in a byte, else 2. Deflate mops up the rest. */
    private static int indexWidth(final int paletteSize) {
        return paletteSize <= 256 ? 1 : 2;
    }

    private static void writeIndices(final DataOutputStream out, final int[] indices, final int width)
            throws IOException {
        if (width == 1) {
            for (final int index : indices) {
                out.writeByte(index);
            }
        } else {
            for (final int index : indices) {
                out.writeShort(index);
            }
        }
    }

    private static int[] readIndices(final DataInputStream in, final int count, final int width)
            throws IOException {
        final int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = width == 1 ? in.readUnsignedByte() : in.readUnsignedShort();
        }
        return indices;
    }

    private static void writePalette(final DataOutputStream out, final List<String> palette) throws IOException {
        writeVarInt(out, palette.size());
        for (final String entry : palette) {
            out.writeUTF(entry);
        }
    }

    private static List<String> readPalette(final DataInputStream in) throws IOException {
        final int size = readVarInt(in);
        // Bound BEFORE allocating: size is off the wire/disk. At most 65536 entries are ever addressable
        // (indices are 1 or 2 bytes wide), so a larger count is malformed, not merely large.
        if (size < 0 || size > CsLodProtocol.MAX_PALETTE_SIZE) {
            throw new IOException("CSLOD record: palette size " + size + " out of range [0, "
                    + CsLodProtocol.MAX_PALETTE_SIZE + "]");
        }
        // Do not presize from the count -- each entry is a further readUTF that hits EOF if the record is
        // short, so a lie is caught without pre-allocating.
        final List<String> palette = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            palette.add(in.readUTF());
        }
        return palette;
    }

    private static void writeVarInt(final DataOutputStream out, final int value) throws IOException {
        int remaining = value;
        while ((remaining & 0xFFFFFF80) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining & 0x7F);
    }

    private static int readVarInt(final DataInputStream in) throws IOException {
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
