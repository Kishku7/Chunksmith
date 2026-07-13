package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DoS bounds tests (doctrine class D19): every count/length a decoder reads off the wire (or off a
 * region-file header whose bytes may have arrived over the wire) is validated against the ceilings in
 * {@link CsLodProtocol} BEFORE anything is allocated.
 *
 * <p>Each "hostile" case crafts a tiny packet that CLAIMS a huge count. The proof that the guard fires
 * before allocation is that the decoder throws a checked {@link IOException} -- NOT an
 * {@link OutOfMemoryError} or {@link NegativeArraySizeException} -- and returns immediately. If the old
 * {@code new ArrayList<>(count)} / {@code new byte[len]} still ran first, a claimed
 * {@link Integer#MAX_VALUE} would OOM the JVM here instead. Each "honest" case proves the boundary is
 * inclusive: a message exactly at the ceiling still decodes byte-for-byte.
 */
public class CsLodBoundsTest {

    // ------------------------------------------------------------------ server hello (dimension count)

    @Test
    public void helloRejectsHugeDimensionCountWithoutAllocating() throws Exception {
        final byte[] payload = helloBytes(Integer.MAX_VALUE, /*writeEntries=*/0);
        assertThrowsIOException(() -> CsLodMessages.decodeServerHello(reader(payload)));
    }

    @Test
    public void helloRejectsNegativeDimensionCount() throws Exception {
        final byte[] payload = helloBytes(-1, 0);
        assertThrowsIOException(() -> CsLodMessages.decodeServerHello(reader(payload)));
    }

    @Test
    public void helloRejectsOneOverTheCeiling() throws Exception {
        final byte[] payload = helloBytes(CsLodProtocol.MAX_HELLO_DIMENSIONS + 1, 0);
        assertThrowsIOException(() -> CsLodMessages.decodeServerHello(reader(payload)));
    }

    @Test
    public void helloAtExactlyTheCeilingStillDecodes() throws Exception {
        final int n = CsLodProtocol.MAX_HELLO_DIMENSIONS;
        final byte[] payload = helloBytes(n, n);
        final CsLodMessages.ServerHello hello = CsLodMessages.decodeServerHello(reader(payload));
        assertEquals(n, hello.dimensions().size());
        assertEquals("d0", hello.dimensions().get(0));
    }

    // ------------------------------------------------------------------ region index (region count)

    @Test
    public void indexRejectsHugeRegionCountWithoutAllocating() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeUTF("minecraft:overworld");
            out.writeInt(Integer.MAX_VALUE);
        }
        assertThrowsIOException(() -> CsLodMessages.decodeRegionIndex(reader(raw.toByteArray())));
    }

    @Test
    public void indexRoundTripsAtTheCeiling() throws Exception {
        final int n = CsLodProtocol.MAX_INDEX_REGIONS;
        final List<CsLodMessages.RegionEntry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(new CsLodMessages.RegionEntry(i, -i, i * 31L, i * 7L));
        }
        final byte[] encoded = CsLodMessages.encode(new CsLodMessages.RegionIndex("minecraft:overworld", entries));
        final DataInputStream in = reader(encoded);
        assertEquals(CsLodProtocol.S2C_INDEX, in.readByte());
        final CsLodMessages.RegionIndex decoded = CsLodMessages.decodeRegionIndex(in);
        assertEquals(n, decoded.regions().size());
        assertEquals(entries.get(n - 1), decoded.regions().get(n - 1));
    }

    // ------------------------------------------------------------------ region slice (payload length)

    @Test
    public void sliceRejectsHugePayloadLengthWithoutAllocating() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeUTF("minecraft:overworld");
            out.writeInt(0);
            out.writeInt(0);
            out.writeBoolean(true);
            out.writeInt(Integer.MAX_VALUE);
        }
        assertThrowsIOException(() -> CsLodMessages.decodeRegionSlice(reader(raw.toByteArray())));
    }

    @Test
    public void sliceRejectsNegativePayloadLength() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeUTF("minecraft:overworld");
            out.writeInt(0);
            out.writeInt(0);
            out.writeBoolean(true);
            out.writeInt(-1);
        }
        assertThrowsIOException(() -> CsLodMessages.decodeRegionSlice(reader(raw.toByteArray())));
    }

    @Test
    public void sliceAtExactlyTheCeilingStillDecodes() throws Exception {
        final byte[] data = new byte[CsLodProtocol.MAX_SLICE_BYTES];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        final byte[] encoded = CsLodMessages.encode(
                new CsLodMessages.RegionSlice("minecraft:overworld", 1, 2, true, data));
        final DataInputStream in = reader(encoded);
        assertEquals(CsLodProtocol.S2C_CHUNK, in.readByte());
        final CsLodMessages.RegionSlice slice = CsLodMessages.decodeRegionSlice(in);
        assertEquals(data.length, slice.data().length);
    }

    // ------------------------------------------------------------------ codec palette (varint size)

    @Test
    public void codecRejectsHugePaletteSizeWithoutAllocating() throws Exception {
        final byte[] record = recordWithBlockPaletteVarint(Integer.MAX_VALUE);
        assertThrowsIOException(() -> CsLodCodec.decode(record));
    }

    @Test
    public void codecRejectsNegativePaletteSize() throws Exception {
        // A varint that decodes to a negative int (high bit set in the fifth group).
        final byte[] record = recordWithBlockPaletteVarint(-1);
        assertThrowsIOException(() -> CsLodCodec.decode(record));
    }

    @Test
    public void codecDecodesAPaletteAtTheCeiling() throws Exception {
        final int n = CsLodProtocol.MAX_PALETTE_SIZE;
        final List<String> blockPalette = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            blockPalette.add("b" + i);
        }
        // Zero sections so no per-voxel indices are written -- this isolates the palette-size path.
        final CsLodChunk chunk = new CsLodChunk("minecraft:overworld", 0, 0, -4,
                blockPalette, List.of("minecraft:plains"), List.of());
        final CsLodChunk decoded = CsLodCodec.decode(CsLodCodec.encode(chunk));
        assertNotNull(decoded);
        assertEquals(n, decoded.getBlockPalette().size());
    }

    // ------------------------------------------------------------------ region store (header slot length)

    @Test
    public void regionStoreRejectsHugeSlotLengthWithoutAllocating() throws Exception {
        final Path root = Files.createTempDirectory("cslod-bounds");
        try {
            // Header is 1024 slots x 8 bytes. slotIndex(0,0) == 0, so slot 0 points at a bogus record
            // that claims Integer.MAX_VALUE bytes -- exactly the shape an in-band write from a hostile
            // server could leave on disk.
            final int headerBytes = 32 * 32 * 8;
            final byte[] file = new byte[headerBytes + 16];
            final ByteArrayOutputStream slot = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(slot)) {
                out.writeInt(headerBytes);        // offset > 0
                out.writeInt(Integer.MAX_VALUE);  // length -- the trap
            }
            System.arraycopy(slot.toByteArray(), 0, file, 0, 8);
            Files.write(root.resolve("r.0.0.cslod"), file);

            final CsLodRegionStore store = new CsLodRegionStore(root);
            try {
                assertThrowsIOException(() -> store.read(0, 0));
            } finally {
                store.close();
            }
        } finally {
            delete(root);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static DataInputStream reader(final byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    /** The body of an S2C_HELLO after the message-id byte, with {@code count} claimed and {@code writeEntries} present. */
    private static byte[] helloBytes(final int count, final int writeEntries) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(CsLodProtocol.VERSION);
            out.writeBoolean(true);
            out.writeInt(0);
            out.writeUTF("");
            out.writeInt(count);
            for (int i = 0; i < writeEntries; i++) {
                out.writeUTF("d" + i);
            }
        }
        return raw.toByteArray();
    }

    /** A Deflate-compressed CSLOD record whose block-palette varint claims {@code paletteSize}. */
    private static byte[] recordWithBlockPaletteVarint(final int paletteSize) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(raw))) {
            out.writeInt(CsLodCodec.MAGIC);
            out.writeShort(CsLodCodec.VERSION);
            out.writeUTF("minecraft:overworld");
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeByte(0); // sectionCount
            writeVarInt(out, paletteSize); // block palette size -- the trap
        }
        return raw.toByteArray();
    }

    private static void writeVarInt(final DataOutputStream out, final int value) throws IOException {
        int remaining = value;
        while ((remaining & 0xFFFFFF80) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining & 0x7F);
    }

    private interface IoRunnable {
        void run() throws IOException;
    }

    private static void assertThrowsIOException(final IoRunnable body) {
        try {
            body.run();
            fail("expected an IOException from an out-of-range wire count");
        } catch (final IOException expected) {
            assertTrue("guard must report the offending value",
                    expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    private static void delete(final Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final Exception ignored) {
                    // Temp dir cleanup; a leftover is not worth failing a test over.
                }
            });
        }
    }
}
