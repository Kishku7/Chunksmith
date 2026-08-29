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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks chunk tickets added and released, as two running totals.
 *
 * <p>Totals rather than a gauge: a leak is the difference between two of them, and a double-release
 * shows up too, which a gauge would silently absorb. An attempt to read the chunk system's own
 * {@code toDrop} set failed as a measurement for the opposite reason -- that set is emptied every single
 * tick, so sampling it reads zero on a healthy server and a sick one alike.
 *
 * <p>The last instrument this problem needed. A pregen accumulated chunk holders without bound (the
 * runaway {@link ChunkResidency} was built to measure) and was still climbing past 17,000 with every
 * plausible culprit ruled out one at a time: the settle window (disabled entirely, retention continued),
 * its cap, player tickets (nobody online), and heap pressure (40 pct at the time). A starved unload
 * budget went too; see {@link UnloadDiagnostics} for what vanilla's {@code ChunkMap.processUnloads} does
 * and does not budget. "Are OUR tickets still on those chunks?" had been asked three times and answered
 * by inference each time, badly.
 */
public final class TicketLedger {

    private static final AtomicLong added = new AtomicLong();
    private static final AtomicLong removed = new AtomicLong();

    private static final AtomicLong peak = new AtomicLong();

    private TicketLedger() {
    }

    public static void noteAdd() {
        final long out = added.incrementAndGet() - removed.get();
        peak.accumulateAndGet(out, Math::max);
    }

    public static void noteRemove() {
        removed.incrementAndGet();
    }

    public static long added() {
        return added.get();
    }

    public static long removed() {
        return removed.get();
    }

    public static long outstanding() {
        return added.get() - removed.get();
    }

    public static long peak() {
        return peak.get();
    }

    public static void reset() {
        added.set(0L);
        removed.set(0L);
        peak.set(0L);
    }

    /** Returns one line for the debug command. No percent sign; the sender formats it. See ChunkResidency#describe. */
    public static String describe() {
        return String.format("added=%d removed=%d outstanding=%d peak=%d",
                added.get(), removed.get(), outstanding(), peak.get());
    }
}
