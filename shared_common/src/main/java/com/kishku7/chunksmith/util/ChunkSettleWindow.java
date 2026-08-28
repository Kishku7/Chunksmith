package com.kishku7.chunksmith.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a just-generated chunk LOADED for a moment after we are done with it, so that other mods which
 * react to a chunk being generated still have something to work with.
 *
 * <p><b>Why this exists.</b> A pregen drops a chunk's ticket the instant the future completes, which is
 * what keeps its memory flat. A mod that hooks "a new chunk appeared" and acts on a later server tick
 * then finds the chunk -- and everything around it -- already gone, so it defers, and keeps deferring
 * for the whole run. Measured: Millenaire queues a candidate on {@code ChunkEvent.Load} and checks
 * {@code level.hasChunksAt(pos +/- villageRadius)} on a later tick; against Chunksmith 3.2.4 on a
 * NeoForge 1.21.1 pregen that scored <b>309 spawn attempts, 309 deferrals, zero villages</b>
 * (mod_support #14). The same shape of bug applies to anything acting on a chunk after we moved on.
 *
 * <p><b>What is held.</b> The condition is not "my chunk is loaded" but "my chunk AND ITS NEIGHBOURS
 * are loaded", and Chunksmith sweeps in a spatial pattern, so a chunk's north and south neighbours can
 * be a whole ring apart in generation order -- hundreds or thousands of chunks -- and a fixed-size FIFO
 * of recent chunks would hold the wrong ones. So the rule is spatial, not temporal: <b>a chunk's ticket
 * is released once all eight of its neighbours have also been generated</b>, plus a short delay so the
 * other mod's tick gets a turn. What is held is therefore the SWEEP FRONTIER and nothing else.
 *
 * <p><b>Bounded bookkeeping.</b> A set of every generated chunk would grow with the whole run (millions
 * of entries on a big pregen) to answer a question only ever asked about the frontier. Instead each
 * position carries a COUNT of how many of its nine have arrived, and an entry is dropped the moment it
 * can no longer be needed.
 *
 * <p><b>Why the frontier still needs a hard cap.</b> The neighbourhood rule bounds the frontier only
 * while every position in it eventually gets all nine. A chunk the run SKIPS -- already generated with
 * its LOD present, or outside the shape -- is never offered, so the chunks beside it are held for the
 * whole run: a resumed or partly pregenerated world leaks steadily, and that is the common case. And a
 * genuinely huge selection has a genuinely huge perimeter. Both were live on the pregen that left a
 * server holding far more chunks than the run could ever need (see {@link ChunkResidency}), so past
 * {@code maxHeld} the OLDEST held chunk is released -- age is exactly the evidence that a chunk's
 * neighbourhood is not coming.
 *
 * <p><b>What a held chunk actually costs.</b> Not one chunk. The ticket is at FULL level and the
 * distance manager propagates that level outward a ring at a time, so each held ticket keeps about 25
 * chunks resident with it -- measured on a live pre-gen (20 held -> 3,507 resident; ~400 held -> 10,167
 * resident). That multiplier is why the cap is a small number and why raising it is a memory decision.
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

    /**
     * Chunk -> the ticket release we have not run yet. The frontier. Insertion-ordered on purpose:
     * eviction is oldest-first and the iteration order IS the age order, which a plain HashMap could
     * not answer without a second structure.
     */
    private final Map<Long, Runnable> held = new LinkedHashMap<>();

    /** Chunk -> the tick its release becomes due. Only chunks whose neighbourhood has closed. */
    private final Map<Long, Long> due = new HashMap<>();

    private final long delayTicks;

    /** Hard ceiling on the frontier. Zero means unbounded (the pre-3.5.0 behaviour). */
    private final long maxHeld;

    private long releasedCount;

    private long evictedCount;

    private boolean drained;

    /**
     * @param delayTicks how long to keep a chunk after its neighbourhood closes. Zero releases as soon
     *                   as the neighbourhood is complete, which suffices for a mod acting on the same
     *                   tick; a small positive value covers one that acts a tick or two later.
     */
    public ChunkSettleWindow(final long delayTicks) {
        this(delayTicks, 0L);
    }

    /**
     * @param delayTicks how long to keep a chunk after its neighbourhood closes; see above
     * @param maxHeld    the most chunks this window may hold at once. Past it the oldest is released
     *                   early. Zero means unbounded, which is only safe when every offered chunk is
     *                   guaranteed to have its neighbourhood closed -- see the class javadoc.
     */
    public ChunkSettleWindow(final long delayTicks, final long maxHeld) {
        this.delayTicks = Math.max(0L, delayTicks);
        this.maxHeld = Math.max(0L, maxHeld);
    }

    /**
     * Hand over a generated chunk and the release that would normally have run immediately.
     *
     * <p>Every path out of this object -- including {@link #drain} -- ends in that release running
     * exactly once. A ticket that is never released is a chunk that never unloads.
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
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                bumpAndTest(chunkX + dx, chunkZ + dz, now);
            }
        }
        releaseDue(now);
        evictBeyondCap();
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
     * Release everything held right now, but keep the window usable.
     *
     * <p>For when a throttle gate has HELD generation. A neighbourhood closes only when new chunks
     * arrive, so with dispatch stopped the frontier sits at its cap holding the very tickets that stop
     * the unloading the gate is waiting for -- the alternative is a run that cannot restart. Unlike
     * {@link #drain} this does NOT retire the window.
     */
    public void releaseAllHeld() {
        final List<Long> keys = new ArrayList<>(this.held.keySet());
        for (final Long key : keys) {
            this.due.remove(key);
            run(key);
        }
    }

    /**
     * Release EVERYTHING still held, due or not, and forget the bookkeeping. Called when a task
     * finishes, is cancelled, or the server is stopping: the frontier then has no more neighbours
     * coming, so waiting would leak every ticket on the edge of the run.
     */
    public void drain() {
        this.drained = true;
        final List<Long> keys = new ArrayList<>(this.held.keySet());
        for (final Long key : keys) {
            this.due.remove(key);
            run(key);
        }
        this.arrived.clear();
        this.due.clear();
    }

    /**
     * Release the oldest held chunks until the frontier is back within its cap. Runs on every offer, so
     * it sheds one entry per arrival rather than thousands at the moment the cap is first crossed: the
     * frontier tracks the cap instead of sawtoothing around it.
     */
    private void evictBeyondCap() {
        if (this.maxHeld <= 0L || this.held.size() <= this.maxHeld) {
            return;
        }
        while (this.held.size() > this.maxHeld) {
            final Iterator<Map.Entry<Long, Runnable>> it = this.held.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            final Long oldest = it.next().getKey();
            this.due.remove(oldest);
            this.evictedCount++;
            run(oldest);
        }
    }

    public boolean isDrained() {
        return this.drained;
    }

    public int heldCount() {
        return this.held.size();
    }

    public int trackedCount() {
        return this.arrived.size();
    }

    public long releasedCount() {
        return this.releasedCount;
    }

    /**
     * How many were released EARLY because the frontier hit its cap. Its own counter rather than folded
     * into {@code releasedCount} because a large number here means the frontier is not behaving the way
     * the neighbourhood rule assumes, which an operator should be able to see rather than infer.
     */
    public long evictedCount() {
        return this.evictedCount;
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
            // A complete neighbourhood around a position we are not holding can never become
            // interesting again. Dropping it is what keeps the map the size of the frontier.
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
     * Drop bookkeeping for positions that are complete but not held. Only ever a handful at a time: a
     * position becomes complete exactly once and the branch above already removes the common case.
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
