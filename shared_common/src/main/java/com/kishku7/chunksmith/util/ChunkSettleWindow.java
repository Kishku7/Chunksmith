package com.kishku7.chunksmith.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Keeps a just-generated chunk LOADED for a moment after we are done with it, so that other mods which
 * react to a chunk being generated still have something to work with.
 *
 * <p><b>Why this exists.</b> A pregen adds a ticket, generates the chunk, and drops the ticket the instant
 * the future completes. That is what keeps a pregen's memory flat, and for pure terrain it is exactly
 * right. But a mod that hooks "a new chunk appeared" and then does its work on a later server tick finds
 * that the chunk -- and everything around it -- is already gone. It cannot place anything, so it defers,
 * and it keeps deferring for the whole run.
 *
 * <p>That is not a hypothetical. Millenaire queues a candidate on {@code ChunkEvent.Load} and, on a later
 * tick, checks {@code level.hasChunksAt(pos +/- villageRadius)} before it places a village. Measured
 * against Chunksmith 3.2.4 on a NeoForge 1.21.1 pregen: <b>309 spawn attempts, 309 deferrals, zero
 * villages</b> (mod_support #14). Nothing was wrong with the other mod -- we were pulling the floor out
 * from under it. Nothing here knows that mod's name, and nothing here should: the same shape of bug
 * applies to anything that acts on a chunk after we have moved on.
 *
 * <p><b>What is held, and why it is not just "the last N chunks".</b> The condition such a mod needs is
 * not "my chunk is loaded", it is "my chunk AND ITS NEIGHBOURS are loaded". Chunksmith sweeps in a spatial
 * pattern, so a chunk's north and south neighbours can be a whole ring apart in generation order --
 * hundreds or thousands of chunks. A fixed-size FIFO of recent chunks would therefore hold the wrong ones
 * and still fail, while a FIFO big enough to span a ring would hold far too many.
 *
 * <p>So the rule is spatial, not temporal: <b>a chunk's ticket is released once all eight of its
 * neighbours have also been generated</b>, plus a short delay so the other mod's tick actually gets a
 * turn. What is held at any moment is therefore the SWEEP FRONTIER and nothing else -- bounded by the
 * shape of the pattern rather than by a number somebody guessed, shrinking to nothing as the run
 * finishes, and it is exactly "the affected chunks".
 *
 * <p><b>Bounded bookkeeping.</b> The obvious implementation keeps every generated chunk in a set and asks
 * "are your nine present?" -- but that set grows with the whole run (millions of entries on a big pregen)
 * to answer a question only ever asked about the frontier. Instead each position carries a COUNT of how
 * many of its nine have arrived; a count reaching nine is the same answer, and an entry is dropped the
 * moment it can no longer be needed. Memory is proportional to the frontier and its perimeter, not to the
 * size of the run.
 *
 * <p>Deliberately MC-free (a chunk is a packed long, a release is a {@link Runnable}) so it can be
 * unit-tested without a server. Not thread-safe by construction: every call is made from the server
 * thread, which is also the only thread allowed to touch a chunk ticket.
 */
public final class ChunkSettleWindow {

    /** A full 3x3 neighbourhood. */
    private static final int COMPLETE = 9;

    /** Position -> how many of its nine neighbours (itself included) have been generated. */
    private final Map<Long, Integer> arrived = new HashMap<>();

    /** Chunk -> the ticket release we have not run yet. The frontier. */
    private final Map<Long, Runnable> held = new HashMap<>();

    /** Chunk -> the tick its release becomes due. Only chunks whose neighbourhood has closed. */
    private final Map<Long, Long> due = new HashMap<>();

    private final long delayTicks;

    private long releasedCount;

    /**
     * @param delayTicks how long to keep a chunk after its neighbourhood closes. Zero releases as soon as
     *                   the neighbourhood is complete, which already suffices for a mod acting on the same
     *                   tick; a small positive value covers one that acts a tick or two later.
     */
    public ChunkSettleWindow(final long delayTicks) {
        this.delayTicks = Math.max(0L, delayTicks);
    }

    /**
     * Hand over a generated chunk and the release that would normally have run immediately.
     *
     * <p>Running that release is this class's responsibility from here on, and every path out of this
     * object ends in it running exactly once -- including {@link #drain}. A ticket that is never released
     * is a chunk that never unloads, so there is no failure mode in which we may simply forget one.
     *
     * @param now the current game tick, used only to time the delay
     */
    public void offer(final int chunkX, final int chunkZ, final long now, final Runnable release) {
        final long key = key(chunkX, chunkZ);
        if (this.held.putIfAbsent(key, release) != null) {
            // Already holding this chunk (a re-run over ground covered earlier in the same session). The
            // first release is the live one; running this one too would drop a ticket we still hold.
            return;
        }
        // This arrival completes a count for itself and for each of its eight neighbours, and for nothing
        // else -- so only those nine are worth touching.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                bumpAndTest(chunkX + dx, chunkZ + dz, now);
            }
        }
        releaseDue(now);
    }

    /** Release everything whose delay has elapsed. Safe to call every tick; cheap when nothing is due. */
    public void releaseDue(final long now) {
        if (this.due.isEmpty()) {
            return;
        }
        List<Long> ready = null;
        for (final Map.Entry<Long, Long> entry : this.due.entrySet()) {
            if (entry.getValue() <= now) {
                if (ready == null) {
                    ready = new ArrayList<>();
                }
                ready.add(entry.getKey());
            }
        }
        if (ready == null) {
            return;
        }
        for (final Long key : ready) {
            this.due.remove(key);
            run(key);
        }
    }

    /**
     * Release EVERYTHING still held, due or not, and forget the bookkeeping.
     *
     * <p>Called when a task finishes, is cancelled, or the server is stopping. The frontier at that moment
     * has no more neighbours coming, so waiting for a neighbourhood that will never close would leak every
     * ticket on the edge of the run.
     */
    public void drain() {
        final List<Long> keys = new ArrayList<>(this.held.keySet());
        for (final Long key : keys) {
            this.due.remove(key);
            run(key);
        }
        this.arrived.clear();
        this.due.clear();
    }

    /** How many chunks are held right now -- the live size of the frontier. */
    public int heldCount() {
        return this.held.size();
    }

    /** How many positions carry bookkeeping right now. Asserted by the test to prove it stays bounded. */
    public int trackedCount() {
        return this.arrived.size();
    }

    /** How many tickets this window has released. Counters, because a silent leak looks like success. */
    public long releasedCount() {
        return this.releasedCount;
    }

    /** Has this position seen all nine of its neighbourhood? Package-visible so the test asserts the rule. */
    boolean neighbourhoodComplete(final int chunkX, final int chunkZ) {
        final Integer count = this.arrived.get(key(chunkX, chunkZ));
        return count != null && count >= COMPLETE;
    }

    private void bumpAndTest(final int chunkX, final int chunkZ, final long now) {
        final long key = key(chunkX, chunkZ);
        final int count = this.arrived.merge(key, 1, Integer::sum);
        if (count < COMPLETE) {
            return;
        }
        if (this.held.containsKey(key)) {
            this.due.putIfAbsent(key, now + this.delayTicks);
        } else {
            // A complete neighbourhood around a position we are not holding can never become interesting
            // again -- either we already released it, or it is not ours to release. Drop the entry rather
            // than carry it for the rest of the run: this is what keeps the map the size of the frontier.
            this.arrived.remove(key);
        }
    }

    private void run(final Long key) {
        final Runnable release = this.held.remove(key);
        if (release != null) {
            this.releasedCount++;
            this.arrived.remove(key);
            release.run();
        }
        pruneStale();
    }

    /**
     * Drop bookkeeping for positions that are complete but not held.
     *
     * <p>Only ever a handful at a time: a position becomes complete exactly once, and the branch above
     * already removes the common case. This catches the ordering in which a position completes BEFORE the
     * chunk we hold there is released.
     */
    private void pruneStale() {
        if (this.arrived.size() <= this.held.size() * COMPLETE + COMPLETE) {
            return;
        }
        final Iterator<Map.Entry<Long, Integer>> it = this.arrived.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, Integer> entry = it.next();
            if (entry.getValue() >= COMPLETE && !this.held.containsKey(entry.getKey())) {
                it.remove();
            }
        }
    }

    static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
