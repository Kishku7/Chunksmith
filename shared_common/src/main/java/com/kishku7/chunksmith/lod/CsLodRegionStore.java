package com.kishku7.chunksmith.lod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * On-disk store for CSLOD chunk records.
 *
 * <p>Anvil-shaped, deliberately: one region file per 32x32 chunks, a fixed header of (offset, length)
 * slots, then the compressed records. Writes append the payload and THEN rewrite the header slot, so
 * a torn write loses at most the one chunk being written -- never the file.
 *
 * <pre>
 *   &lt;world&gt;/chunksmith/lod/&lt;dim&gt;/r.&lt;rx&gt;.&lt;rz&gt;.cslod
 *
 *   header: 1024 slots x 8 bytes = 8192 bytes
 *           slot = i64 offset (0 = absent) ... stored as i32 offset + i32 length
 *   body:   Deflate-compressed CsLodCodec records, appended
 * </pre>
 *
 * <p><b>No native dependencies, no locks, no daemon.</b> That is the point: voxy's RocksDB is
 * process-exclusive, DH's SQLite is DH's, and our store has to be readable by a second process (a
 * backfill tool) without fighting either of them.
 *
 * <p>Rewriting a chunk appends a new record and re-points the slot; the old bytes are left behind as
 * garbage. Pregen writes each chunk once, so in the normal case there is nothing to reclaim. A
 * compaction pass can come later if a use case ever needs it.
 */
public final class CsLodRegionStore {

    /** Chunks per region axis. */
    public static final int REGION_CHUNKS = 32;

    private static final int SLOTS = REGION_CHUNKS * REGION_CHUNKS;
    private static final int SLOT_BYTES = 8;
    private static final int HEADER_BYTES = SLOTS * SLOT_BYTES;

    private final Path root;
    private final Map<Long, RandomAccessFile> open = new HashMap<>();

    /**
     * @param root the per-dimension directory, e.g. {@code <world>/chunksmith/lod/minecraft_overworld}
     */
    public CsLodRegionStore(final Path root) {
        this.root = root;
    }

    /**
     * Persist one chunk record.
     *
     * @return the number of COMPRESSED bytes written (so callers can account size without
     *         re-encoding the record)
     */
    public synchronized int write(final CsLodChunk chunk) throws IOException {
        final byte[] payload = CsLodCodec.encode(chunk);
        final int rx = Math.floorDiv(chunk.getChunkX(), REGION_CHUNKS);
        final int rz = Math.floorDiv(chunk.getChunkZ(), REGION_CHUNKS);
        final RandomAccessFile file = region(rx, rz);

        final int slot = slotIndex(chunk.getChunkX(), chunk.getChunkZ());
        final long offset = file.length();

        // Append the payload FIRST, then point the header slot at it. A crash between the two leaves
        // orphaned bytes (harmless) rather than a slot pointing at a half-written record.
        file.seek(offset);
        file.write(payload);

        file.seek((long) slot * SLOT_BYTES);
        file.writeInt((int) offset);
        file.writeInt(payload.length);
        return payload.length;
    }

    /** Read one chunk record back, or null if this chunk was never written. */
    public synchronized CsLodChunk read(final int chunkX, final int chunkZ) throws IOException {
        final int rx = Math.floorDiv(chunkX, REGION_CHUNKS);
        final int rz = Math.floorDiv(chunkZ, REGION_CHUNKS);
        final Path path = regionPath(rx, rz);
        if (!Files.exists(path)) {
            return null;
        }
        final RandomAccessFile file = region(rx, rz);
        final int slot = slotIndex(chunkX, chunkZ);
        file.seek((long) slot * SLOT_BYTES);
        final int offset = file.readInt();
        final int length = file.readInt();
        if (offset <= 0 || length <= 0) {
            return null;
        }
        final byte[] payload = new byte[length];
        file.seek(offset);
        file.readFully(payload);
        return CsLodCodec.decode(payload);
    }

    /**
     * Walk every record in a CSLOD tree and hand each decoded chunk to the visitor.
     *
     * <p>Static and stateless on purpose: this is how a SECOND process (a backfill/verify tool) reads
     * the store while the game holds nothing. Plain files, no lock, no native DB -- unlike voxy's
     * process-exclusive RocksDB, which is the whole reason we keep our own store.
     *
     * @return the number of records visited
     */
    public static int forEachChunk(final Path root, final ChunkVisitor visitor) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int visited = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            final java.util.List<Path> regions = walk
                    .filter(path -> path.getFileName().toString().endsWith(".cslod"))
                    .sorted()
                    .toList();
            for (final Path region : regions) {
                visited += forEachChunkIn(region, visitor);
            }
        }
        return visited;
    }

    /**
     * Walk ONE region's records.
     *
     * <p>Why this exists: a client that keeps pulling as the player travels must inject only the regions it
     * just received. Re-walking the whole tree on every refresh would re-decode and re-push terrain the
     * renderer already has -- on a big store that is minutes of wasted work per move, and with voxy it means
     * re-ingesting hundreds of thousands of sections. Scope the walk to what actually arrived.
     *
     * @return the number of records visited (0 if the region file does not exist)
     */
    public static int forEachChunkInRegion(final Path root, final int regionX, final int regionZ,
                                           final ChunkVisitor visitor) throws IOException {
        final Path region = root.resolve("r." + regionX + "." + regionZ + ".cslod");
        if (!Files.isRegularFile(region)) {
            return 0;
        }
        return forEachChunkIn(region, visitor);
    }

    private static int forEachChunkIn(final Path region, final ChunkVisitor visitor) throws IOException {
        int visited = 0;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            for (int slot = 0; slot < SLOTS; slot++) {
                file.seek((long) slot * SLOT_BYTES);
                final int offset = file.readInt();
                final int length = file.readInt();
                if (offset <= 0 || length <= 0) {
                    continue;
                }
                final byte[] payload = new byte[length];
                file.seek(offset);
                file.readFully(payload);
                visitor.visit(CsLodCodec.decode(payload));
                visited++;
            }
        }
        return visited;
    }

    /** Visitor for {@link #forEachChunk}. May throw to abort the walk. */
    @FunctionalInterface
    public interface ChunkVisitor {
        void visit(CsLodChunk chunk) throws IOException;
    }

    /** Close every open region file. */
    public synchronized void close() throws IOException {
        IOException first = null;
        for (final RandomAccessFile file : open.values()) {
            try {
                file.close();
            } catch (final IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        open.clear();
        if (first != null) {
            throw first;
        }
    }

    private RandomAccessFile region(final int rx, final int rz) throws IOException {
        final long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        RandomAccessFile file = open.get(key);
        if (file != null) {
            return file;
        }
        final Path path = regionPath(rx, rz);
        Files.createDirectories(path.getParent());
        file = new RandomAccessFile(path.toFile(), "rw");
        if (file.length() < HEADER_BYTES) {
            file.setLength(HEADER_BYTES);
        }
        open.put(key, file);
        return file;
    }

    private Path regionPath(final int rx, final int rz) {
        return root.resolve("r." + rx + "." + rz + ".cslod");
    }

    private static int slotIndex(final int chunkX, final int chunkZ) {
        final int localX = Math.floorMod(chunkX, REGION_CHUNKS);
        final int localZ = Math.floorMod(chunkZ, REGION_CHUNKS);
        return localZ * REGION_CHUNKS + localX;
    }
}
