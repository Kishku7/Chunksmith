package com.kishku7.chunksmith.lod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Writes {@link CsLodChunk} records to a {@link CsLodRegionStore} off the server thread.
 *
 * <p>The extraction from a live chunk happens on the main thread (it must -- the chunk is unloaded
 * the instant the ticket is released); everything after that is handed to a single writer thread
 * through a BOUNDED queue.
 *
 * <p><b>Bounded, and never lossy.</b> If the queue is full the offer does NOT drop the chunk: it
 * writes it synchronously on the calling thread. That is slow, and it is meant to be -- it converts
 * "LOD writer fell behind" into visible back-pressure on generation instead of a silently missing
 * chunk. (Voxy WorldGen V2 drops refused chunks and marks them complete forever; we do not.)
 * In practice the generation throttle's LOD governor keeps the queue far below the bound.
 */
public final class CsLodStoreSink implements LodSink {

    private static final Logger LOGGER = Logger.getLogger("Chunksmith");

    private final CsLodRegionStore store;
    private final BlockingQueue<CsLodChunk> queue;
    private final Thread writer;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong synchronousWrites = new AtomicLong();
    private volatile boolean running = true;

    public CsLodStoreSink(final Path root, final int capacity) {
        this.store = new CsLodRegionStore(root);
        this.queue = new ArrayBlockingQueue<>(Math.max(16, capacity));
        this.writer = new Thread(this::drain, "chunksmith-lod-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * @param chunk a {@link CsLodChunk} produced by the platform-side extractor
     * @return true always -- this sink does not lose chunks; see the class note on the synchronous
     *         fallback. Backpressure is expressed through {@link #queueDepth()}, which the
     *         generation throttle watches.
     */
    @Override
    public boolean offer(final Object chunk) {
        if (!(chunk instanceof final CsLodChunk record)) {
            return true;
        }
        if (!queue.offer(record)) {
            // Writer is behind. Do NOT drop: write it here and now, on the caller, and let the
            // resulting slowdown be the backpressure.
            synchronousWrites.incrementAndGet();
            persist(record);
        }
        return true;
    }

    @Override
    public int queueDepth() {
        return queue.size();
    }

    /** Chunks persisted so far. */
    public long getWrittenCount() {
        return written.get();
    }

    /** Compressed bytes written so far -- the number to compare against voxy's ~43 KB/chunk. */
    public long getWrittenBytes() {
        return bytes.get();
    }

    /** How many times the queue was full and we had to write on the caller. Should be ~0. */
    public long getSynchronousWrites() {
        return synchronousWrites.get();
    }

    /** Drain the queue and close the store. Call at task end / server stop. */
    public void shutdown() {
        running = false;
        writer.interrupt();
        try {
            writer.join(10_000L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Anything still queued after the writer stops gets flushed here rather than lost.
        CsLodChunk remaining;
        while ((remaining = queue.poll()) != null) {
            persist(remaining);
        }
        try {
            store.close();
        } catch (final IOException e) {
            LOGGER.warning("Chunksmith: failed to close the LOD store: " + e);
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                final CsLodChunk record = queue.take();
                persist(record);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void persist(final CsLodChunk record) {
        try {
            final int size = store.write(record);
            written.incrementAndGet();
            bytes.addAndGet(size);
        } catch (final IOException e) {
            LOGGER.warning(String.format("Chunksmith: failed to write LOD for chunk %d,%d: %s",
                    record.getChunkX(), record.getChunkZ(), e));
        }
    }

    @Override
    public String toString() {
        return "CsLodStoreSink";
    }
}
