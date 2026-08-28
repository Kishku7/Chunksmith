package com.kishku7.chunksmith.lod;

/**
 * Implementations must be safe to call from the server main thread and must not block.
 */
public interface LodSink {

    /**
     * Returns {@code false} when the sink is saturated. A {@code false} return is backpressure,
     * not an error: the caller must not treat the chunk as done, and should retry it later.
     */
    boolean offer(Object chunk);

    int queueDepth();

    LodSink NOOP = new LodSink() {

        @Override
        public boolean offer(Object chunk) {
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
