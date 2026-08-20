package com.kishku7.chunksmith.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How many chunk tickets Chunksmith has added and not yet given back.
 *
 * <p><b>Why this exists, and why it is the LAST instrument this problem needed.</b> A pregen on a
 * 1.21.11 server accumulated chunks without bound -- 75,045 in the original report, and still climbing
 * past 17,000 with every plausible culprit ruled out one at a time: the settle window (disabled
 * entirely, retention continued), its cap, player tickets (nobody online), and heap pressure (40 pct
 * at the time). Reading vanilla's {@code ChunkMap.processUnloads} ruled out a starved unload budget
 * too -- its {@code toDrop} loop consults no budget and its {@code unloadQueue} drain runs down to
 * 2000 entries regardless.
 *
 * <p>The obvious next question -- "are OUR tickets still on those chunks?" -- was asked three times
 * and answered by inference each time, badly. An attempt to read the chunk system's own
 * {@code toDrop} set failed as a measurement, because that set is emptied every single tick, so
 * sampling it reads zero on a healthy server and a sick one alike.
 *
 * <p>This is the version that cannot lie. Every {@code addTicketWithRadius} increments, every
 * {@code removeTicketWithRadius} decrements, and the difference is the number of tickets Chunksmith
 * genuinely still holds. Near zero while thousands of chunks sit resident means the hold is not ours
 * and the search moves elsewhere. Tracking the resident count means the removal path is broken. There
 * is no third answer and no sampling artefact.
 *
 * <p>Counters, not gauges: a leak is a difference between two totals, and totals also make a
 * double-release visible, which a gauge would silently absorb.
 */
public final class TicketLedger {

    private static final AtomicLong added = new AtomicLong();
    private static final AtomicLong removed = new AtomicLong();

    /** Highest outstanding count seen, so a peak is not lost between two samples. */
    private static final AtomicLong peak = new AtomicLong();

    private TicketLedger() {
    }

    /** One ticket added. Called immediately after the chunk system accepts it. */
    public static void noteAdd() {
        final long out = added.incrementAndGet() - removed.get();
        peak.accumulateAndGet(out, Math::max);
    }

    /** One ticket handed back. Called immediately after the chunk system accepts the removal. */
    public static void noteRemove() {
        removed.incrementAndGet();
    }

    public static long added() {
        return added.get();
    }

    public static long removed() {
        return removed.get();
    }

    /**
     * Tickets added and not yet removed.
     *
     * <p>May legitimately go NEGATIVE if a release ever runs twice -- which is itself worth knowing,
     * so it is reported rather than clamped.
     */
    public static long outstanding() {
        return added.get() - removed.get();
    }

    public static long peak() {
        return peak.get();
    }

    /** Reset at the start of a run, so one run's ledger is never read as another's. */
    public static void reset() {
        added.set(0L);
        removed.set(0L);
        peak.set(0L);
    }

    /** No literal percent sign: the sender formats this string. See ChunkResidency#describe. */
    public static String describe() {
        return String.format("added=%d removed=%d outstanding=%d peak=%d",
                added.get(), removed.get(), outstanding(), peak.get());
    }
}
