package com.kishku7.chunksmith.util;

/**
 * What the chunk system is actually doing about unloading, in five numbers.
 *
 * <p><b>Why this exists.</b> On 2026-08-20 a drain ran for its full ten-minute ceiling and freed
 * <b>30 chunks out of 22,067</b>, with the pregen paused. Three releases had by then been spent making
 * the unload pass faster, on the assumption that it was starved of budget. Reading
 * {@code ChunkMap.processUnloads} settled it: the {@code toDrop} loop consults no budget at all, and
 * the {@code unloadQueue} drain runs {@code while (unloadQueue.size() - 2000 > 0 || haveTime())} --
 * so vanilla drains it down to 2000 entries even when {@code haveTime} is false. A starved budget
 * therefore cannot produce 30 chunks. The only remaining explanation is that the chunks were never
 * ELIGIBLE: {@code toDrop} was empty because something still held their ticket level.
 *
 * <p>Nothing in the mod could distinguish those two cases, which is why the wrong one was worked on
 * for three releases. These five numbers distinguish them in one line:
 *
 * <ul>
 *   <li>{@code visible} -- chunk holders resident, the number {@code Chunks[S] W:} reports.
 *   <li>{@code toDrop} -- holders the distance manager has decided are no longer needed. If this is
 *       ZERO while {@code visible} is large, nothing is eligible and the problem is TICKETS, not
 *       throughput. That is the whole question.
 *   <li>{@code unloadQueue} -- eligible work waiting to run. Large here means throughput.
 *   <li>{@code pendingUnloads} -- holders removed from the live map, waiting on their save to finish.
 *   <li>{@code hasTickets} -- whether the distance manager still holds any ticket at all.
 * </ul>
 *
 * <p>All five are readable on every supported MC version with no drift: {@code toDrop} is
 * package-private, {@code unloadQueue} and {@code pendingUnloads} are private but reachable by
 * accessor, {@code ChunkMap.size()} and {@code getDistanceManager()} are public, and the names and
 * types are identical from 1.20.1 through 26.3.
 *
 * <p>Deliberately MC-free so the numbers can be read from shared code and printed by a command.
 */
public final class UnloadDiagnostics {

    private static volatile boolean supported;
    private static volatile long visible;
    private static volatile long toDrop;
    private static volatile long unloadQueue;
    private static volatile long pendingUnloads;
    private static volatile boolean hasTickets;

    // Resident chunks bucketed by TICKET LEVEL, sampled on a slow cadence. This is the measurement
    // that separates the last two possibilities. A chunk's ticket level decides whether it may stay
    // loaded: low means something is holding it at a live status, high means nothing wants it and it
    // is merely waiting to be dropped. Chunksmith's own ledger has already proved OUR tickets are not
    // the holder (a few hundred outstanding against eleven thousand resident), so either somebody
    // else's tickets are holding them at a low level, or they are all sitting at a high level and
    // vanilla simply is not dropping them. One histogram tells us which.
    private static volatile long levelTicking;    // <= 33: full / entity-ticking
    private static volatile long levelLoaded;     // 34..43: still loaded, borders
    private static volatile long levelDroppable;  // >= 44: nothing holds it; it should go
    private static volatile long levelSampledAt;

    /** Ticket strings read straight from vanilla for a sample of chunks held at a ticking level. */
    private static volatile String ticketSample = "not sampled yet";

    /** Every ticket on every resident chunk, counted by type. The quantitative form of the above. */
    private static volatile String ticketTally = "not sampled yet";

    private UnloadDiagnostics() {
    }

    /** Publish a reading. Called from the server thread; every argument is a plain size(). */
    public static void report(final long visible, final long toDrop, final long unloadQueue,
                              final long pendingUnloads, final boolean hasTickets) {
        UnloadDiagnostics.visible = visible;
        UnloadDiagnostics.toDrop = toDrop;
        UnloadDiagnostics.unloadQueue = unloadQueue;
        UnloadDiagnostics.pendingUnloads = pendingUnloads;
        UnloadDiagnostics.hasTickets = hasTickets;
        supported = true;
    }

    public static boolean isSupported() {
        return supported;
    }

    public static long toDrop() {
        return toDrop;
    }

    public static long unloadQueue() {
        return unloadQueue;
    }

    /** Publish the ticket-level histogram of the resident set. Sampled on a slow cadence. */
    public static void reportLevels(final long ticking, final long loaded, final long droppable,
                                    final long sampledAtMillis) {
        levelTicking = ticking;
        levelLoaded = loaded;
        levelDroppable = droppable;
        levelSampledAt = sampledAtMillis;
    }

    /** Publish vanilla's own ticket strings for chunks nothing of ours is holding. */
    public static void reportTicketSample(final String sample) {
        ticketSample = sample;
    }

    public static void reportTicketTally(final String tally) {
        ticketTally = tally;
    }

    /** Ticket counts by type across the resident set. */
    public static String describeTicketTally() {
        return ticketTally;
    }

    /** Who is holding them, in vanilla's own words. No inference. */
    public static String describeTicketSample() {
        return ticketSample;
    }

    /** One line naming which of the two remaining explanations the numbers support. */
    public static String describeLevels() {
        if (levelSampledAt <= 0L) {
            return "not sampled yet";
        }
        final long total = levelTicking + levelLoaded + levelDroppable;
        final String verdict;
        if (total <= 0L) {
            verdict = "nothing resident";
        } else if (levelDroppable > total / 2L) {
            verdict = "MOST ARE PAST MAX_LEVEL -- nothing holds them and vanilla is not dropping them";
        } else {
            // Not a leak by itself. Every FULL chunk needs a ring of worldgen context around it, so a
            // pre-gen keeps that ring resident for its whole frontier -- and the frontier's perimeter
            // grows with the radius being generated. Compare 'ticking' against the dispatch limit and
            // the settle cap: if THAT is bounded, this band is the cost of the frontier, not a leak.
            verdict = "mostly worldgen context around the frontier -- judge by 'ticking', not this";
        }
        return String.format("ticking=%d loaded=%d droppable=%d age=%ds -- %s",
                levelTicking, levelLoaded, levelDroppable,
                Math.max(0L, (System.currentTimeMillis() - levelSampledAt) / 1000L), verdict);
    }

    /** Forget the reading. The server is going away. */
    public static void clear() {
        supported = false;
        visible = 0L;
        toDrop = 0L;
        unloadQueue = 0L;
        pendingUnloads = 0L;
        hasTickets = false;
        levelTicking = 0L;
        levelLoaded = 0L;
        levelDroppable = 0L;
        levelSampledAt = 0L;
        ticketSample = "not sampled yet";
        ticketTally = "not sampled yet";
    }

    /**
     * One line, and a plain-English verdict on which of the two cases we are in.
     *
     * <p>Contains no literal percent sign -- {@code Sender.sendMessagePrefixed} runs its message
     * through {@code String.format}, which 3.5.2 learned the hard way.
     */
    public static String describe() {
        if (!supported) {
            return "unavailable on this platform";
        }
        // NO VERDICT ON toDrop == 0. It was tempting and it was wrong: ChunkMap.processUnloads
        // empties toDrop every tick in a loop that consults no budget, so sampling it here reads zero
        // on a healthy server exactly as often as on a sick one. The 3.5.5 build printed "NOTHING IS
        // ELIGIBLE TO UNLOAD" from that zero and it proved nothing at all. These numbers are reported
        // as observations; TicketLedger is what actually answers the question.
        final String verdict;
        if (visible <= 0L) {
            verdict = "nothing resident";
        } else if (unloadQueue > 2000L) {
            verdict = "eligible work is backing up -- a throughput problem";
        } else {
            verdict = "note: toDrop and unloadQueue are drained every tick, so 0 here means nothing";
        }
        return String.format("visible=%d toDrop=%d unloadQueue=%d pendingUnloads=%d hasTickets=%s -- %s",
                visible, toDrop, unloadQueue, pendingUnloads, hasTickets, verdict);
    }
}
