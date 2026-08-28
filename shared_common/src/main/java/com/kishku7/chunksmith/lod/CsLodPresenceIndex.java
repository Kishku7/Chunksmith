package com.kishku7.chunksmith.lod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Does this chunk already have a CSLOD record?" -- answered without decoding anything, so a pregen re-run
 * FILLS LOD HOLES instead of skipping already-generated chunks forever (they are never loaded, so the LOD
 * hook never sees them) or rewriting the whole selection ({@code forceLoadExistingChunks: true}).
 *
 * <p><b>Why it is cheap.</b> {@link CsLodRegionStore} is Anvil-shaped: every region file opens with a fixed
 * 8192-byte header of 1024 slots x (i32 offset, i32 length), and a record exists iff its slot has
 * {@code offset > 0 && length > 0}. One 8 KB sequential read per REGION FILE gives presence for all 1024 of
 * its chunks -- no per-chunk seek, no record bodies, no second open.
 *
 * <p><b>Why it stays correct DURING a run.</b> The store's writer thread is asynchronous, so the on-disk
 * header lags dispatch by the queue depth. The bitmap, not the disk, is this run's authority:
 * {@link #markLod(int, int)} sets the bit when the chunk is dispatched down the load path, which is exactly
 * the path that fires the LOD hook. Regions load from disk once, on first query; a re-read after eviction
 * can at worst miss async writes and report a chunk absent -- one redundant load, never a missing LOD.
 *
 * <p>Thread-safety: {@code synchronized}. {@link #hasLod} runs on the pregen dispatch thread,
 * {@link #markLod} on the chunk-completion thread.
 */
public final class CsLodPresenceIndex {

    /** Chunks per region axis -- must match {@link CsLodRegionStore#REGION_CHUNKS}. */
    private static final int REGION_CHUNKS = CsLodRegionStore.REGION_CHUNKS;

    private static final int SLOTS = REGION_CHUNKS * REGION_CHUNKS;
    private static final int SLOT_BYTES = 8;
    private static final int HEADER_BYTES = SLOTS * SLOT_BYTES;

    /** 1024 slots -> 1024 bits -> 16 longs per region. */
    private static final int BITMAP_LONGS = SLOTS / Long.SIZE;

    /** How many region bitmaps to keep. Region-ordered iteration needs one or two; 256 x 128 B = 32 KB. */
    private static final int MAX_CACHED_REGIONS = 256;

    private final Path root;

    /** regionKey -> 1024-bit presence bitmap. Access-ordered: the eldest-accessed region is evicted. */
    private final RegionLru bitmaps = new RegionLru(MAX_CACHED_REGIONS);

    /**
     * Access-ordered LRU over region bitmaps. A named class rather than an anonymous subclass purely so it
     * can carry a {@code serialVersionUID}: the build runs {@code -Xlint:all} with zero warnings tolerated.
     */
    private static final class RegionLru extends LinkedHashMap<Long, long[]> {

        private static final long serialVersionUID = 1L;

        private final int max;

        RegionLru(final int max) {
            super(64, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, long[]> eldest) {
            return size() > max;
        }
    }

    // Cost accounting, so the price of the check is reportable rather than assumed.
    private final AtomicLong regionsLoaded = new AtomicLong();
    private final AtomicLong headerBytesRead = new AtomicLong();
    private final AtomicLong loadNanos = new AtomicLong();
    private final AtomicLong queries = new AtomicLong();

    /**
     * @param root the per-dimension CSLOD directory, e.g.
     *             {@code <world>/chunksmith/lod/minecraft_overworld}
     */
    public CsLodPresenceIndex(final Path root) {
        this.root = root;
    }

    public Path getRoot() {
        return root;
    }

    /**
     * True when a CSLOD record already exists for this chunk.
     *
     * <p>False on any I/O problem: an unreadable header is "no LOD here", so a broken or truncated region
     * file can only make us rebuild LODs we had, never make us skip a chunk that has none.
     */
    public synchronized boolean hasLod(final int chunkX, final int chunkZ) {
        queries.incrementAndGet();
        final long[] bitmap = bitmapFor(regionX(chunkX), regionZ(chunkZ));
        final int slot = slotIndex(chunkX, chunkZ);
        return (bitmap[slot >>> 6] & (1L << (slot & 63))) != 0L;
    }

    /**
     * Record that this chunk is now (or is about to be) backed by a CSLOD record. Called at DISPATCH, not
     * at write-completion -- see the class doc.
     */
    public synchronized void markLod(final int chunkX, final int chunkZ) {
        final long[] bitmap = bitmapFor(regionX(chunkX), regionZ(chunkZ));
        final int slot = slotIndex(chunkX, chunkZ);
        bitmap[slot >>> 6] |= 1L << (slot & 63);
    }

    /** How many chunks in this region currently read as present. Test/diagnostic helper. */
    public synchronized int countInRegion(final int regionX, final int regionZ) {
        int count = 0;
        for (final long word : bitmapFor(regionX, regionZ)) {
            count += Long.bitCount(word);
        }
        return count;
    }

    public synchronized void invalidate() {
        bitmaps.clear();
    }

    public long getRegionsLoaded() {
        return regionsLoaded.get();
    }

    public long getHeaderBytesRead() {
        return headerBytesRead.get();
    }

    public long getLoadNanos() {
        return loadNanos.get();
    }

    public long getQueries() {
        return queries.get();
    }

    /**
     * An immutable snapshot of the cost counters. The index is cached per dimension for the SERVER's
     * lifetime, so its raw counters are cumulative across every pregen it has run: snapshot at task start,
     * report the delta (see {@link #describeCostSince}).
     */
    public static final class Cost {

        private final long queries;
        private final long regionsLoaded;
        private final long headerBytesRead;
        private final long loadNanos;

        private Cost(final long queries, final long regionsLoaded, final long headerBytesRead,
                     final long loadNanos) {
            this.queries = queries;
            this.regionsLoaded = regionsLoaded;
            this.headerBytesRead = headerBytesRead;
            this.loadNanos = loadNanos;
        }
    }

    public Cost cost() {
        return new Cost(queries.get(), regionsLoaded.get(), headerBytesRead.get(), loadNanos.get());
    }

    /**
     * One line: the real, measured cost of the presence check SINCE {@code before} -- THIS run, not the
     * server's lifetime.
     */
    public String describeCostSince(final Cost before) {
        final long asked = queries.get() - before.queries;
        final long loaded = regionsLoaded.get() - before.regionsLoaded;
        final long bytes = headerBytesRead.get() - before.headerBytesRead;
        final double millis = (loadNanos.get() - before.loadNanos) / 1e6;
        return String.format(
                "lod presence check: %d chunks queried, %d region headers read (%d KB), %.1f ms total (%.4f ms per 1k chunks)",
                asked, loaded, bytes / 1024L, millis,
                asked == 0 ? 0.0 : millis * 1000.0 / asked);
    }

    /**
     * Count every CSLOD record under a store root, by header only -- the honest number for
     * {@code /cslod status}. Reads 8 KB per region file and decodes no records. Static and stateless like
     * {@link CsLodRegionStore#forEachChunk}, so a second process can call it.
     */
    public static long countRecords(final Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        long total = 0L;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            final java.util.List<Path> regions = walk
                    .filter(path -> path.getFileName().toString().endsWith(".cslod"))
                    .toList();
            for (final Path region : regions) {
                total += countIn(region);
            }
        }
        return total;
    }

    private static long countIn(final Path region) throws IOException {
        final byte[] header = new byte[HEADER_BYTES];
        int read = 0;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            final int available = (int) Math.min(HEADER_BYTES, file.length());
            if (available > 0) {
                file.readFully(header, 0, available);
                read = available;
            }
        }
        long count = 0L;
        final int slots = read / SLOT_BYTES;
        for (int slot = 0; slot < slots; slot++) {
            final int base = slot * SLOT_BYTES;
            if (readInt(header, base) > 0 && readInt(header, base + 4) > 0) {
                count++;
            }
        }
        return count;
    }


    /** The region's bitmap, read from its header on first use and cached thereafter. */
    private long[] bitmapFor(final int regionX, final int regionZ) {
        final long key = regionKey(regionX, regionZ);
        long[] bitmap = bitmaps.get(key);
        if (bitmap == null) {
            bitmap = readHeader(regionX, regionZ);
            bitmaps.put(key, bitmap);
        }
        return bitmap;
    }

    /**
     * Read one region file's 8 KB header and fold it into a 1024-bit presence bitmap. A missing region file
     * is not an error -- it is the common case on a world that has never had LODs built.
     */
    private long[] readHeader(final int regionX, final int regionZ) {
        final long[] bitmap = new long[BITMAP_LONGS];
        final Path path = root.resolve("r." + regionX + "." + regionZ + ".cslod");
        if (!Files.isRegularFile(path)) {
            regionsLoaded.incrementAndGet();
            return bitmap;
        }
        final long start = System.nanoTime();
        final byte[] header = new byte[HEADER_BYTES];
        int read = 0;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            // A file shorter than the header is truncated/half-created: read what is there, treat the rest
            // as absent, rather than throwing.
            final int available = (int) Math.min(HEADER_BYTES, file.length());
            if (available > 0) {
                file.readFully(header, 0, available);
                read = available;
            }
        } catch (final IOException e) {
            // Unreadable header -> report every chunk absent -> we rebuild rather than silently skip.
            return bitmap;
        } finally {
            loadNanos.addAndGet(System.nanoTime() - start);
            regionsLoaded.incrementAndGet();
        }
        headerBytesRead.addAndGet(read);

        final int slots = read / SLOT_BYTES;
        for (int slot = 0; slot < slots; slot++) {
            final int base = slot * SLOT_BYTES;
            final int offset = readInt(header, base);
            final int length = readInt(header, base + 4);
            // Exactly the presence test CsLodRegionStore.read() and forEachChunkIn() use. Offset 0 is
            // never real: the store reserves the first HEADER_BYTES, so payloads start at 8192 or later.
            if (offset > 0 && length > 0) {
                bitmap[slot >>> 6] |= 1L << (slot & 63);
            }
        }
        return bitmap;
    }

    /** Big-endian i32, matching {@link RandomAccessFile#writeInt}. */
    private static int readInt(final byte[] buffer, final int index) {
        return ((buffer[index] & 0xFF) << 24)
                | ((buffer[index + 1] & 0xFF) << 16)
                | ((buffer[index + 2] & 0xFF) << 8)
                | (buffer[index + 3] & 0xFF);
    }

    private static int regionX(final int chunkX) {
        return Math.floorDiv(chunkX, REGION_CHUNKS);
    }

    private static int regionZ(final int chunkZ) {
        return Math.floorDiv(chunkZ, REGION_CHUNKS);
    }

    private static long regionKey(final int regionX, final int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int slotIndex(final int chunkX, final int chunkZ) {
        final int localX = Math.floorMod(chunkX, REGION_CHUNKS);
        final int localZ = Math.floorMod(chunkZ, REGION_CHUNKS);
        return localZ * REGION_CHUNKS + localX;
    }
}
