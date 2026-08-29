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
 * What the chunk system is actually doing about unloading, in five numbers.
 *
 * <p>A drain once ran for its full ten-minute ceiling and freed <b>30 chunks out of 22,067</b>, with the
 * pregen paused, after three releases spent making the unload pass faster on the assumption it was
 * starved of budget. {@code ChunkMap.processUnloads} settled it: the {@code toDrop} loop consults no
 * budget at all, and the {@code unloadQueue} drain runs
 * {@code while (unloadQueue.size() - 2000 > 0 || haveTime())}, so vanilla drains to 2000 entries even
 * when {@code haveTime} is false. A starved budget cannot produce 30 chunks; the chunks were never
 * eligible, because something still held their ticket level.
 *
 * <p>Nothing in the mod could distinguish those two cases, which is why the wrong one was worked on for
 * three releases. {@code visible} is holders resident; {@code toDrop} is holders the distance manager
 * has decided are no longer needed, so zero there while {@code visible} is large means the problem is
 * tickets, not throughput; {@code unloadQueue} is eligible work waiting, so large means throughput;
 * {@code pendingUnloads} is holders waiting on their save; {@code hasTickets} is whether the distance
 * manager holds any ticket at all.
 *
 * <p>All five are readable on every supported MC version with no drift: {@code toDrop} is
 * package-private, {@code unloadQueue} and {@code pendingUnloads} private but reachable by accessor,
 * and the names and types are identical from 1.20.1 through 26.3.
 */
public final class UnloadDiagnostics {

    private static volatile boolean supported;
    private static volatile long visible;
    private static volatile long toDrop;
    private static volatile long unloadQueue;
    private static volatile long pendingUnloads;
    private static volatile boolean hasTickets;

    // Resident chunks bucketed by ticket level, sampled on a slow cadence. Low level means something
    // holds the chunk at a live status; high means nothing wants it and it is waiting to be dropped.
    // Our own ledger already proved our tickets are not the holder (a few hundred outstanding against
    // eleven thousand resident), so this histogram says which of the two remaining answers it is.
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

    /** Publishes a reading. Called from the server thread; every argument is a plain size(). */
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

    /** Publishes the ticket-level histogram of the resident set. Sampled on a slow cadence. */
    public static void reportLevels(final long ticking, final long loaded, final long droppable,
                                    final long sampledAtMillis) {
        levelTicking = ticking;
        levelLoaded = loaded;
        levelDroppable = droppable;
        levelSampledAt = sampledAtMillis;
    }

    /** Publishes vanilla's own ticket strings for chunks nothing of ours is holding. */
    public static void reportTicketSample(String sample) {
        ticketSample = sample;
    }

    public static void reportTicketTally(String tally) {
        ticketTally = tally;
    }

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
        long total = levelTicking + levelLoaded + levelDroppable;
        String verdict;
        if (total <= 0L) {
            verdict = "nothing resident";
        } else if (levelDroppable > total / 2L) {
            verdict = "MOST ARE PAST MAX_LEVEL: nothing holds them and vanilla is not dropping them";
        } else {
            // Not a leak by itself: every FULL chunk needs a ring of worldgen context, so a pre-gen
            // keeps that ring resident for its whole frontier. Compare 'ticking' against the dispatch
            // limit and the settle cap: if that is bounded, this band is the frontier, not a leak.
            verdict = "mostly worldgen context around the frontier; judge by 'ticking', not this";
        }
        return String.format("ticking=%d loaded=%d droppable=%d age=%ds (%s)",
                levelTicking, levelLoaded, levelDroppable,
                Math.max(0L, (System.currentTimeMillis() - levelSampledAt) / 1000L), verdict);
    }

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
     * Returns one line, and a plain-English verdict on which of the two cases we are in. Contains no
     * literal percent sign, because {@code Sender.sendMessagePrefixed} formats its message, which 3.5.2
     * learned hard.
     */
    public static String describe() {
        if (!supported) {
            return "unavailable on this platform";
        }
        // No verdict on toDrop == 0. ChunkMap.processUnloads empties toDrop every tick in a loop that
        // consults no budget, so sampling it reads zero on a healthy server exactly as often as on a
        // sick one. The 3.5.5 build printed "NOTHING IS ELIGIBLE TO UNLOAD" from that zero and it
        // proved nothing. TicketLedger is what actually answers the question.
        String verdict;
        if (visible <= 0L) {
            verdict = "nothing resident";
        } else if (unloadQueue > 2000L) {
            verdict = "eligible work is backing up, a throughput problem";
        } else {
            verdict = "note: toDrop and unloadQueue are drained every tick, so 0 here means nothing";
        }
        return String.format("visible=%d toDrop=%d unloadQueue=%d pendingUnloads=%d hasTickets=%s (%s)",
                visible, toDrop, unloadQueue, pendingUnloads, hasTickets, verdict);
    }
}
