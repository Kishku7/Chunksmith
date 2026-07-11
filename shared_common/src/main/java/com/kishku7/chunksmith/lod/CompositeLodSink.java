package com.kishku7.chunksmith.lod;

import java.util.List;

/**
 * Fans one generated chunk out to several sinks.
 *
 * <p>The two sinks want DIFFERENT objects: {@link VoxyLodSink}-style consumers want the live
 * platform chunk, while {@link CsLodStoreSink} wants an extracted {@link CsLodChunk}. Each sink
 * ignores what it does not recognise (see their {@code offer} contracts), so the platform hook can
 * simply offer both objects.
 *
 * <p>Queue depth is the MAX across sinks -- the generation throttle should back off for whichever
 * consumer is furthest behind, not an average that hides it.
 */
public final class CompositeLodSink implements LodSink {

    private final List<LodSink> sinks;

    public CompositeLodSink(final List<LodSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    public List<LodSink> getSinks() {
        return sinks;
    }

    @Override
    public boolean offer(final Object chunk) {
        boolean accepted = true;
        for (final LodSink sink : sinks) {
            if (!sink.offer(chunk)) {
                accepted = false;
            }
        }
        return accepted;
    }

    @Override
    public int queueDepth() {
        int depth = 0;
        for (final LodSink sink : sinks) {
            depth = Math.max(depth, sink.queueDepth());
        }
        return depth;
    }

    @Override
    public String toString() {
        return "CompositeLodSink" + sinks;
    }
}
