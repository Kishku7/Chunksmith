/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.util;

/**
 * What the dispatch pipeline is doing right now, for {@code /cs debug} (mod_support #32).
 *
 * <p>Everything here already governed a run and none of it was visible. The width the controller
 * had settled on only ever appeared in a throttle notice, and the threshold it steers against
 * appeared nowhere at all -- so "the pregen keeps pausing" could not be answered by looking, only
 * by guessing. It cost mod_support #20 a fortnight and two wrong causes.
 *
 * <p>Published by the running {@code GenerationTask}; static because the run is a singleton and
 * this is how every other governor here reports (see {@link ChunkResidency}, {@link TickBudget}).
 * Values are {@code -1} when no run has published yet, which reads as "no task" rather than as a
 * zero somebody might take for a measurement.
 */
public final class DispatchStats {

    private static volatile int inFlight = -1;
    private static volatile int limit = -1;
    private static volatile int goodWidth = -1;
    private static volatile int ceiling = -1;
    private static volatile int sinkQueue = -1;

    private DispatchStats() {
    }

    /**
     * Publishes a sample of the pipeline.
     *
     * @param chunksInFlight   chunk requests dispatched and not yet completed
     * @param currentLimit     the width the controller is running at
     * @param knownGoodWidth   the widest width known to have run healthily
     * @param configuredMax    the ceiling from {@code dispatchMaxConcurrent}
     * @param rendererQueue    chunks queued for the renderer, or -1 where no sink reports one
     */
    public static void publish(int chunksInFlight, int currentLimit, int knownGoodWidth,
                               int configuredMax, int rendererQueue) {
        inFlight = chunksInFlight;
        limit = currentLimit;
        goodWidth = knownGoodWidth;
        ceiling = configuredMax;
        sinkQueue = rendererQueue;
    }

    /** Clears the sample. Called when a run ends so a stale width cannot be read as live. */
    public static void clear() {
        inFlight = -1;
        limit = -1;
        goodWidth = -1;
        ceiling = -1;
        sinkQueue = -1;
    }

    /**
     * @return a one-line readout of the pipeline, or a plain statement that nothing is running
     */
    public static String describe() {
        if (limit < 0) {
            return "no generation task running";
        }
        // goodWidth starts at the ceiling and only means something once an overload has moved it.
        String known = goodWidth >= ceiling ? "none yet" : Integer.toString(goodWidth);
        return String.format(
                "inFlight=%d width=%d of %d (ceiling) knownGood=%s rendererQueue=%s",
                inFlight, limit, ceiling, known,
                sinkQueue < 0 ? "n/a" : Integer.toString(sinkQueue));
    }
}
