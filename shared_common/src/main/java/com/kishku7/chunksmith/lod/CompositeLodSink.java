package com.kishku7.chunksmith.lod;

import java.util.List;

public final class CompositeLodSink implements LodSink {

    private final List<LodSink> sinks;

    public CompositeLodSink(List<LodSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    public List<LodSink> getSinks() {
        return sinks;
    }

    @Override
    public boolean offer(Object chunk) {
        boolean accepted = true;
        for (LodSink sink : sinks) {
            if (!sink.offer(chunk)) {
                accepted = false;
            }
        }
        return accepted;
    }

    @Override
    public int queueDepth() {
        int depth = 0;
        for (LodSink sink : sinks) {
            depth = Math.max(depth, sink.queueDepth());
        }
        return depth;
    }

    @Override
    public String toString() {
        return "CompositeLodSink" + sinks;
    }
}
