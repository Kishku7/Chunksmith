package com.kishku7.chunksmith.lod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Does this chunk already have a CSLOD record?" -- answered without decoding anything.
 *
 * <p>This is what makes a pregen re-run FILL LOD HOLES instead of either skipping them forever
 * (the old behaviour: an already-generated chunk was skipped, was never loaded, so the LOD hook
 * never saw it) or re-writing every chunk in the selection ({@code forceLoadExistingChunks: true},
 * which reloads and rewrites the lot).
 *
 * <p><b>Why it is cheap.</b> {@link CsLodRegionStore} is Anvil-shaped: every region file opens with a
 * fixed 8192-byte header of 1024 slots x (i32 offset, i32 length). A record exists iff its slot has
 * {@code offset > 0 && length > 0}. So one 8 KB sequential read per REGION FILE yields presence for
 * all 1024 of its chunks -- we never seek per chunk, never re-stat, never touch the record bodies,
 * and never open the file again. That header read is amortised over 1024 chunks; against the cost of
 * loading even one chunk it rounds to nothing.
 *
 * <p><b>Why it stays correct DURING a run.</b> The store's writer thread is asynchronous, so the
 * on-disk header lags dispatch by the queue depth. The bitmap, not the disk, is this run's authority:
 * {@link #markLod(int, int)} sets the bit at the moment the chunk is dispatched down the load path
 * (which is exactly the path that fires the LOD hook), so a chunk generated early in a run can never
 * be re-processed later in the same run. Regions are loaded from disk exactly once, on first query.
 *
 * <p>Eviction is safe. The pregen iterates in region order, so the working set is one or two regions;
 * the LRU below is far larger than it needs to be. Even if a region were evicted and re-loaded, the
 * only possible harm is re-reading a header whose async writes have not landed yet -- which would
 * report a chunk as absent and cost one redundant chunk load, never a WRONG or missing LOD.
 *
 * <p>Thread-safety: {@code synchronized}. {@link #hasLod} is called on the pregen dispatch thread and
 * {@link #markLod} on the chunk-completion thread; both are a handful of operations on a
 * {@code long[]} under the lock, which is noise next to a chunk load.
 *
 * <p>This class is MC-agnostic on purpose (it is pure file I/O over our own format), so it lives in
 * shared_common next to the store and is unit-testable without a game.
 */
public final class CsLodPresenceIndex {

    /** Chunks per region axis -- must match {@link CsLodRegionStore#REGION_CHUNKS}. */
    private static final int REGION_CHUNKS = CsLodRegionStore.REGION_CHUNKS;

    private static final int SLOTS = REGION_CHUNKS * REGION_CHUNKS;
    private static final int SLOT_BYTES = 8;
    private static final int HEADER_BYTES = SLOTS * SLOT_BYTES;

    /** 1024 slots -> 1024 bits -> 16 longs per region. */
    private static final int BITMAP_LONGS = SLOTS / Long.SIZE;

    /**
     * How many region bitmaps to keep. Region-ordered iteration needs one or two; 256 regions is
     * 256 x 128 bytes = 32 KB, which buys total immunity to any iteration order we might ever use.
     */
    private static final int MAX_CACHED_REGIONS = 256;

    private final Path root;

    /** regionKey -> 1024-bit presence bitmap. Access-ordered: the eldest-accessed region is evicted. */
    private final RegionLru bitmaps = new RegionLru(MAX_CACHED_REGIONS);

    /**
     * Access-ordered LRU over region bitmaps.
     *
     * <p>A named class rather than an anonymous subclass purely so it can carry a
     * {@code serialVersionUID}: {@link LinkedHashMap} is {@link java.io.Serializable}, and the build
     * runs {@code -Xlint:all} with zero warnings tolerated.
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
     *             {@code <world>/chunksmith/lod/minecraft_overworld} -- the same path
     *             {@code LodSupport.storeRoot(level)} hands to {@link CsLodStoreSink}
     */
    public CsLodPresenceIndex(final Path root) {
        this.root = root;
    }

    /** The directory this index reports on. */
    public Path getRoot() {
        return root;
    }

    /**
     * True when a CSLOD record already exists for this chunk.
     *
     * <p>False on any I/O problem: a header we cannot read is treated as "no LOD here", so the worst a
     * broken or truncated region file can do is make us rebuild LODs we already had. It can never make
     * us skip a chunk that has none -- that failure direction is the one that produces the silent holes
     * this whole feature exists to eliminate.
     */
    public synchronized boolean hasLod(final int chunkX, final int chunkZ) {
        queries.incrementAndGet();
        final long[] bitmap = bitmapFor(regionX(chunkX), regionZ(chunkZ));
        final int slot = slotIndex(chunkX, chunkZ);
        return (bitmap[slot >>> 6] & (1L << (slot & 63))) != 0L;
    }

    /**
     * Record that this chunk is now (or is about to be) backed by a CSLOD record.
     *
     * <p>Called at DISPATCH, not at write-completion: the store writes asynchronously, so waiting for
     * the disk would leave a window in which the same run could re-process the chunk. Dispatch down the
     * load path is what fires the LOD hook, so it is the honest moment to claim the chunk.
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

    /** Drop every cached bitmap. Next query re-reads from disk. */
    public synchronized void invalidate() {
        bitmaps.clear();
    }

    /** Region files whose header we actually read. */
    public long getRegionsLoaded() {
        return regionsLoaded.get();
    }

    /** Total header bytes read from disk. */
    public long getHeaderBytesRead() {
        return headerBytesRead.get();
    }

    /** Total nanoseconds spent reading headers -- the entire disk cost of the presence check. */
    public long getLoadNanos() {
        return loadNanos.get();
    }

    /** How many chunks were asked about. */
    public long getQueries() {
        return queries.get();
    }

    /**
     * An immutable snapshot of the cost counters.
     *
     * <p>The index is cached per dimension for the SERVER's lifetime, so its raw counters are cumulative
     * across every pregen the server has run. A task that reported them directly would claim a cost it
     * did not pay (a second run would report double the queries). Snapshot at task start, report the
     * delta -- see {@link #describeCostSince}.
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

    /** Snapshot the cost counters, to be handed back to {@link #describeCostSince} later. */
    public Cost cost() {
        return new Cost(queries.get(), regionsLoaded.get(), headerBytesRead.get(), loadNanos.get());
    }

    /**
     * One line: the real, measured cost of the presence check SINCE {@code before}.
     *
     * <p>Reports THIS run, not the server's lifetime -- a number that overstates what a run actually
     * cost is worse than no number at all.
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
     * Count every CSLOD record under a store root, by header only.
     *
     * <p>The honest number for {@code /cslod status}: it is what an operator compares against the chunk
     * count to answer "does my store actually cover my world?". Reads 8 KB per region file and decodes
     * no records, so it is cheap enough to run from a command on a large store.
     *
     * <p>Static and stateless, like {@link CsLodRegionStore#forEachChunk}: a second process can call it.
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

    // -------------------------------------------------------------------------------------------

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
     * Read one region file's 8 KB header and fold it into a 1024-bit presence bitmap.
     *
     * <p>ONE open, ONE sequential read, ONE close. A missing region file is not an error -- it is the
     * common case on a world that has never had LODs built, and it means "none of these 1024 chunks
     * has a record".
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
            // A file shorter than the header is a truncated/half-created region: read what is there
            // and treat the remainder as absent, rather than throwing.
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
            // Exactly the presence test CsLodRegionStore.read() and forEachChunkIn() use. Offset 0
            // is never a real record: the store reserves the first HEADER_BYTES for the header, so
            // the earliest payload can only start at 8192.
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
