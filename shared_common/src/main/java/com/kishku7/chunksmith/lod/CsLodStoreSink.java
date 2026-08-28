package com.kishku7.chunksmith.lod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CsLodStoreSink implements LodSink {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private final CsLodRegionStore store;
    private final BlockingQueue<CsLodChunk> queue;
    private final Thread writer;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong synchronousWrites = new AtomicLong();
    private volatile boolean running = true;

    public CsLodStoreSink(Path root, int capacity) {
        this.store = new CsLodRegionStore(root);
        this.queue = new ArrayBlockingQueue<>(Math.max(16, capacity));
        this.writer = new Thread(this::drain, "chunksmith-lod-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public boolean offer(Object chunk) {
        if (!(chunk instanceof final CsLodChunk record)) {
            return true;
        }
        if (!queue.offer(record)) {
            // Writer is behind. Do not drop: write it here and now, on the caller, and let the
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

    public long getWrittenCount() {
        return written.get();
    }

    /** Returns the compressed bytes written so far, against voxy's ~43 KB/chunk. */
    public long getWrittenBytes() {
        return bytes.get();
    }

    public long getSynchronousWrites() {
        return synchronousWrites.get();
    }

    public void shutdown() {
        running = false;
        writer.interrupt();
        try {
            writer.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        CsLodChunk remaining;
        while ((remaining = queue.poll()) != null) {
            persist(remaining);
        }
        try {
            store.close();
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: failed to close the LOD store: " + e);
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                CsLodChunk record = queue.take();
                persist(record);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void persist(CsLodChunk record) {
        try {
            int size = store.write(record);
            written.incrementAndGet();
            bytes.addAndGet(size);
        } catch (IOException e) {
            LOGGER.warn(String.format("Chunksmith: failed to write LOD for chunk %d,%d: %s",
                    record.getChunkX(), record.getChunkZ(), e));
        }
    }

    @Override
    public String toString() {
        return "CsLodStoreSink";
    }
}
