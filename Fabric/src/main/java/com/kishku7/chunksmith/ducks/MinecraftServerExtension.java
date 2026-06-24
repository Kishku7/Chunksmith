package com.kishku7.chunksmith.ducks;

import java.util.function.BooleanSupplier;

public interface MinecraftServerExtension {
    void chunksmith$runChunkSystemHousekeeping(BooleanSupplier haveTime);

    void chunksmith$markChunkSystemHousekeeping();

    /**
     * Smoothed mean milliseconds-per-tick of the server main thread, sampled only
     * while a generation task is active. Used as the primary I/O-throttle signal.
     * A value at or near 50 ms means the server is holding a full 20 TPS; higher
     * means it is falling behind (the direct symptom of I/O saturation).
     */
    double chunksmith$getMillisPerTick();
}
