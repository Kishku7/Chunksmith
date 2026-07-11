package com.kishku7.chunksmith.lod;

/**
 * A sink for LOD data derived from freshly generated chunks.
 *
 * <p>Deliberately MC-agnostic: shared code never sees a {@code LevelChunk}, so the chunk is passed
 * as {@link Object} and downcast by the platform-side implementation. This mirrors the Platform
 * facade used elsewhere in shared_common.
 *
 * <p>Implementations must be safe to call from the server main thread and must not block.
 */
public interface LodSink {

    /**
     * Offer a freshly generated chunk to the sink.
     *
     * <p>Returns {@code false} when the sink is saturated. A {@code false} return is BACKPRESSURE,
     * not an error: the caller must NOT treat the chunk as done, and should retry it later.
     *
     * @param chunk the platform chunk object; implementations downcast as needed
     * @return true if the chunk was accepted (or the sink does not want it), false if refused
     */
    boolean offer(Object chunk);

    /**
     * Approximate number of items currently queued inside the sink.
     *
     * <p>Feeds the generation throttle so that pregen cannot outrun LOD ingestion.
     *
     * @return queued item count, or 0 if the sink does not queue
     */
    int queueDepth();

    /** A sink that accepts everything, stores nothing, and never applies backpressure. */
    LodSink NOOP = new LodSink() {

        @Override
        public boolean offer(final Object chunk) {
            return true;
        }

        @Override
        public int queueDepth() {
            return 0;
        }

        @Override
        public String toString() {
            return "LodSink.NOOP";
        }
    };
}
