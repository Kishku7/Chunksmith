package com.kishku7.chunksmith.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How many chunks the server is holding in memory, and whether a pregen still owes the server a drain.
 *
 * <p>Every other throttle signal Chunksmith has measures how fast work goes in -- tick time, per-chunk
 * latency, the write queue, the LOD sink. None measured what had piled up and not gone out. On a live
 * server a pregen ran with 75,045 chunk holders resident, ten times the sweep frontier the run could
 * need, and nothing in the mod could see it.
 *
 * <p>3.5.0 then got two things wrong, both measured on Zion 2026-08-20. Absolute counts turned out to be
 * meaningless: a cap of "20,000 resident" tripped on a server whose ordinary resident set was already
 * near it, so the gate closed on the baseline and never opened. The question is how many we have added
 * -- hence {@link #baseline()}, captured at run start, and a gate reading the delta.
 *
 * <p>And the backlog outlives the task. 3.5.0 drove the unload pass only while a task was active; when
 * it paused, the remainder fell to vanilla's budgeted pass, which does nothing once the tick is over
 * budget -- which it is precisely because of the retained chunks. Measured: 39,064 chunks still resident
 * nineteen minutes after the pregen stopped, no players online, 51 ms per tick, heap pinned at 8.7 GB,
 * permanent until a restart. So {@link #isDraining()} keeps the pass running until they go.
 *
 * <p>A stale reading is deliberately not discarded. A server whose main thread has stopped ticking stops
 * publishing, and its chunks are not unloading either, so a recent-but-frozen reading is still the truth.
 * Only a reading older than {@link #FRESH_MILLIS} is thrown away, so it cannot gate a later run.
 *
 * <p>Static because there is one server per process; volatile because the generation task reads from a
 * worker thread while the server thread publishes.
 */
public final class ChunkResidency {

    // slf4j, not java.util.logging. The loaders route slf4j into the game's own log; JUL output goes
    // nowhere anybody looks, which is why 3.5.3's drain lifecycle lines never appeared on the server
    // even though the code ran. (GsonConfig and TaskScheduler still use JUL and are equally invisible.)
    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** How long a published reading stays usable. Beyond this the count is treated as unknown. */
    public static final long FRESH_MILLIS = 60_000L;

    /**
     * How much of the run's own growth may remain before a drain is called done. Not zero: chunks a
     * player is holding, and chunks the world legitimately keeps, are counted in the same number, and
     * demanding an exact return to baseline would arm the unload pass for ever on a server in use.
     */
    private static final long DRAIN_MARGIN = 512L;

    /** Give up draining if the count has not fallen at all for this long -- the rest is pinned. */
    private static final long DRAIN_STALL_MILLIS = 30_000L;

    /** Absolute ceiling on a drain, so a pathological world cannot arm the unload pass for ever. */
    private static final long DRAIN_MAX_MILLIS = 10 * 60_000L;

    private static volatile long loadedChunks = -1L;
    private static volatile long reportedAtMillis;

    private static volatile long baseline = -1L;

    private static volatile boolean draining;
    private static volatile long drainStartedMillis;
    private static volatile long drainBestSeen = Long.MAX_VALUE;
    private static volatile long drainProgressMillis;
    private static volatile long drainStartedAt = -1L;
    private static volatile boolean drainOnFullBudget;
    private static volatile boolean generationHeld;
    private static volatile String lastDrainOutcome = "none yet";

    private ChunkResidency() {
    }

    /**
     * Publish the current resident chunk count. Server thread, once per tick.
     *
     * <p>Published on every tick, not only while a run is active: after 3.5.0 the number was cleared the
     * moment a task ended, which is exactly when the backlog it left behind most needed watching.
     *
     * @param loaded total chunk holders across every level, or negative when the platform cannot say
     */
    public static void report(long loaded) {
        report(loaded, System.currentTimeMillis());
    }

    /**
     * Time-injecting overload. The drain's three timing exits are each a never-wedge guarantee, so they
     * have to be testable without a clock that really passes.
     */
    static void report(long loaded, long now) {
        if (loaded < 0L) {
            return;
        }
        loadedChunks = loaded;
        reportedAtMillis = now;
        if (draining) {
            evaluateDrain(loaded, now);
        }
    }

    /**
     * The most recent resident chunk count, or -1 when there is no usable reading. -1 means "unknown",
     * never "zero": a caller must not read an absent measurement as an empty server and open the taps.
     */
    public static long loadedChunks() {
        return loadedChunksAt(System.currentTimeMillis());
    }

    static long loadedChunksAt(long now) {
        final long value = loadedChunks;
        if (value < 0L) {
            return -1L;
        }
        if (now - reportedAtMillis > FRESH_MILLIS) {
            return -1L;
        }
        return value;
    }

    public static boolean isSupported() {
        return loadedChunks() >= 0L;
    }

    /**
     * Residency when the current run started, or -1 if it was not measurable. This is the number the
     * gate subtracts: everything already resident when a run begins belongs to the server, not to us.
     */
    public static long baseline() {
        return baseline;
    }

    /** How many chunks this run has added, or -1 when either end of the subtraction is unknown. */
    public static long addedChunks() {
        final long now = loadedChunks();
        final long base = baseline;
        if (now < 0L || base < 0L) {
            return -1L;
        }
        return Math.max(0L, now - base);
    }

    public static void noteTaskStart() {
        noteTaskStart(System.currentTimeMillis());
    }

    static void noteTaskStart(long now) {
        baseline = loadedChunksAt(now);
        draining = false;
        drainBestSeen = Long.MAX_VALUE;
    }

    /**
     * A run has ended. Keep the unload pass armed until the chunks it loaded have actually gone. Ending
     * a task is not finishing the work: a released ticket only becomes a freed chunk once the distance
     * manager has propagated it and the unload pass has run.
     */
    public static void noteTaskEnd() {
        noteTaskEnd(System.currentTimeMillis());
    }

    static void noteTaskEnd(long now) {
        draining = true;
        drainStartedMillis = now;
        drainProgressMillis = now;
        final long current = loadedChunksAt(now);
        drainBestSeen = current < 0L ? Long.MAX_VALUE : current;
        drainStartedAt = current;
        // Say what is happening, once at each end. 3.5.1 was correct and silent, which made a drain
        // impossible to debug from a server log; two lines per run is not log spam.
        LOGGER.info(String.format(
                "Chunksmith: pregen finished; draining its chunks. %d resident, %d of them added by this run. %s",
                current, Math.max(0L, current - baseline),
                UnloadDiagnostics.describe() + " | our tickets: " + TicketLedger.describe()));
    }

    public static boolean isDraining() {
        return draining;
    }

    /**
     * Tell the drain whether it is currently being given a real budget.
     *
     * <p>The unload floor is small while players are online, and a drain on that floor makes little
     * measurable progress -- which the stall detector below reads as "nothing left to unload". It gave
     * up for exactly that reason once while a player was online; the player left, and the server sat at
     * 71.5 ms per tick with a full heap until it was restarted, because nothing re-armed it. So the
     * no-progress clock only advances while the drain is allowed to work.
     */
    public static void noteDrainBudget(boolean fullBudget) {
        drainOnFullBudget = fullBudget;
    }

    /**
     * Conditions have improved -- typically the last player has left. Resume draining if the server is
     * still holding more than the run started with. A drain is not a one-shot; treating it as one is
     * what let a server stay degraded indefinitely after the thing blocking the drain went away.
     */
    public static void reconsiderDrain() {
        reconsiderDrain(System.currentTimeMillis());
    }

    static void reconsiderDrain(long now) {
        if (draining) {
            return;
        }
        final long base = baseline;
        final long loaded = loadedChunksAt(now);
        if (base < 0L || loaded < 0L || loaded <= base + DRAIN_MARGIN) {
            return;
        }
        draining = true;
        drainStartedMillis = now;
        drainProgressMillis = now;
        drainBestSeen = loaded;
        drainStartedAt = loaded;
        LOGGER.info(String.format(
                "Chunksmith: resuming the drain now that conditions allow it. %d resident, %d above where the run started.",
                loaded, loaded - base));
    }

    /**
     * Generation has stopped dispatching because one of our gates closed -- residency or heap.
     *
     * <p>Two things follow, both missing when the gate was first tested on a real server. The unload
     * pass should get the full budget: nothing is being generated, and unloading is the only thing that
     * can reopen the gate. And the settle frontier must be let go -- with dispatch stopped no neighbour
     * is ever coming, so the frontier freezes at its cap and prevents the very recovery the gate is
     * waiting for. Measured: 25,638 resident, held for 120 s, count up by 196.
     */
    public static void noteGenerationHeld(boolean held) {
        generationHeld = held;
    }

    public static boolean isGenerationHeld() {
        return generationHeld;
    }

    /** Forget everything. Called when the server stops, so no reading outlives its server. */
    public static void clear() {
        loadedChunks = -1L;
        reportedAtMillis = 0L;
        baseline = -1L;
        draining = false;
        drainBestSeen = Long.MAX_VALUE;
        drainStartedMillis = 0L;
        drainProgressMillis = 0L;
        drainStartedAt = -1L;
        drainOnFullBudget = false;
        generationHeld = false;
        UnloadDiagnostics.clear();
        lastDrainOutcome = "none yet";
    }

    /**
     * Decide whether the drain is finished.
     *
     * <p>Three ways to stop, all needed. Reaching the baseline is success. No progress for {@link
     * #DRAIN_STALL_MILLIS} means the remainder is pinned by something that is not ours -- players,
     * spawn chunks, another mod's tickets. The ceiling backstops a world that trickles down for ever.
     */
    private static void evaluateDrain(long loaded, long now) {
        final long base = baseline;
        if (base >= 0L && loaded <= base + DRAIN_MARGIN) {
            finishDrain(loaded, now, "back to where the run started");
            return;
        }
        if (loaded < drainBestSeen) {
            drainBestSeen = loaded;
            drainProgressMillis = now;
        } else if (!drainOnFullBudget) {
            // Not being given a real budget, so "no progress" proves nothing. Hold the clock rather
            // than let it convict the drain of a stall it never had a chance to avoid.
            drainProgressMillis = now;
        } else if (now - drainProgressMillis >= DRAIN_STALL_MILLIS) {
            finishDrain(loaded, now, "stopped falling on a full budget -- the rest is pinned by something that is not ours");
            return;
        }
        if (now - drainStartedMillis >= DRAIN_MAX_MILLIS) {
            finishDrain(loaded, now, "hit the ten-minute ceiling");
        }
    }

    private static void finishDrain(long loaded, long now, String reason) {
        draining = false;
        final long seconds = Math.max(0L, (now - drainStartedMillis) / 1000L);
        final long freed = drainStartedAt < 0L ? -1L : Math.max(0L, drainStartedAt - loaded);
        lastDrainOutcome = String.format("%s (%d resident, %d freed, %ds)", reason, loaded, freed, seconds);
        // WARN rather than INFO when chunks are left behind: that is the case an operator needs to see,
        // and it is exactly the case 3.5.1 could not distinguish from success.
        final String message = String.format(
                "Chunksmith: drain finished -- %s. %d chunks resident, %d freed, %d above where the run started, took %ds. %s",
                reason, loaded, freed, baseline < 0L ? -1L : Math.max(0L, loaded - baseline), seconds,
                UnloadDiagnostics.describe() + " | our tickets: " + TicketLedger.describe());
        if (baseline >= 0L && loaded > baseline + DRAIN_MARGIN) {
            LOGGER.warn(message);
        } else {
            LOGGER.info(message);
        }
    }

    /**
     * One line describing the whole signal, for {@code /cs debug}. Never throws, never blocks. Contains
     * no literal percent sign: {@code Sender.sendMessagePrefixed} runs its message through
     * {@code String.format}, so a stray sign throws -- which shipped in 3.5.2 and broke the command.
     */
    public static String describe() {
        final long now = loadedChunks();
        final long base = baseline;
        final long added = addedChunks();
        return String.format("resident=%s baseline=%s added=%s draining=%s heap=%dMB of %dMB (%.0f pct) lastDrain=[%s]",
                now < 0L ? "unknown" : Long.toString(now),
                base < 0L ? "unset" : Long.toString(base),
                added < 0L ? "unknown" : Long.toString(added),
                draining,
                HeapPressure.usedMegabytes(),
                HeapPressure.maxMegabytes(),
                HeapPressure.usedPercent(),
                lastDrainOutcome);
    }
}
