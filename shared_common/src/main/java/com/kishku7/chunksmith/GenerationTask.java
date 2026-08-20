package com.kishku7.chunksmith;

import com.kishku7.chunksmith.lod.CsLodPresenceIndex;
import com.kishku7.chunksmith.lod.LodPresence;
import com.kishku7.chunksmith.lod.LodSinks;

import com.kishku7.chunksmith.api.event.task.GenerationCompleteEvent;
import com.kishku7.chunksmith.api.event.task.GenerationProgressEvent;
import com.kishku7.chunksmith.event.task.GenerationTaskFinishEvent;
import com.kishku7.chunksmith.event.task.GenerationTaskUpdateEvent;
import com.kishku7.chunksmith.iterator.ChunkIterator;
import com.kishku7.chunksmith.iterator.ChunkIteratorFactory;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.shape.Shape;
import com.kishku7.chunksmith.shape.ShapeFactory;
import com.kishku7.chunksmith.util.ChunkCoordinate;
import com.kishku7.chunksmith.util.AutoPause;
import com.kishku7.chunksmith.util.ChunkResidency;
import com.kishku7.chunksmith.util.HeapPressure;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.Pair;
import com.kishku7.chunksmith.util.RegionCache;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class GenerationTask implements Runnable {
    private static final int MAX_WORKING_COUNT = Input.tryInteger(System.getProperty("chunksmith.maxWorkingCount")).orElse(50);
    private static final double SAMPLE_INTERVAL = 1000d * Math.max(Input.tryInteger(System.getProperty("chunksmith.sampleInterval")).orElse(30), 30);
    private static final double SAMPLE_SUB_INTERVAL = SAMPLE_INTERVAL / 30;
    private static final long NOTICE_INTERVAL_MS = 10_000L;
    // How long the LOD summary waits for the last in-flight chunks to land before reporting.
    private static final long DRAIN_TIMEOUT_MS = 60_000L;
    // Post-run proof that "generated" meant something -- see verifyGeneratedSample().
    private static final int VERIFY_SAMPLE_MAX = 32;
    private static final long VERIFY_TIMEOUT_MS = 15_000L;
    // How long to let the chunk-write queue drain before believing "not on disk" means anything.
    private static final long VERIFY_WRITE_DRAIN_MS = 30_000L;
    // Only a DECISIVE result is worth reporting. A run that wrote nothing misses ~100% of the
    // sample; a healthy run under a replaced chunk system can still miss a couple to ordinary
    // write lag (measured: 2 of 31 on a run that put all 4225 chunks on disk correctly). Reporting
    // on "any missing" turns the check into noise, and a check people learn to ignore is worse
    // than no check at all.
    private static final int VERIFY_MIN_MISSING = 4;
    private static final double VERIFY_MISSING_FRACTION = 0.5d;
    // Adaptive concurrency uses asymmetric AIMD-style timing: back off quickly under load,
    // ramp back up slowly. Tick health is sampled on a fixed cadence so the limit can fall
    // even when chunk completions have stalled entirely (disk blocked, no callbacks firing).
    private static final long RAMP_INTERVAL_MS = 1_000L;     // at most +1 per second
    private static final long BACKOFF_INTERVAL_MS = 250L;    // at most -1 per 250 ms
    private static final long MSPT_CHECK_INTERVAL_MS = 250L; // how often tick health is evaluated
    private static final double MSPT_BAND = 3.0D;            // dead-band around target to prevent flapping
    private static final long WRITE_CHECK_INTERVAL_MS = 100L; // how often the disk write-queue depth is sampled
    // Drain-stall backpressure. The region writer is a single consecutive-executor thread, so
    // if the queue stops shrinking while still holding work the writer is blocked in fsync
    // (region-file eviction on a saturated disk). Absolute depth misses this -- the queue can
    // stay shallow while each individual flush blocks for seconds. Trip when the queue has held
    // >= MIN_DEPTH with no drain progress for STALL_MILLIS. JVM-tunable; STALL_MILLIS=0 disables.
    private static final long WRITE_STALL_MILLIS = Math.max(0L, Input.tryInteger(System.getProperty("chunksmith.writeStallMillis")).orElse(2000));
    private static final long WRITE_STALL_MIN_DEPTH = Math.max(1L, Input.tryInteger(System.getProperty("chunksmith.writeStallMinDepth")).orElse(16));
    // Chunk-residency backpressure. Sampled on the same cadence as the write queue.
    private static final long RESIDENCY_CHECK_INTERVAL_MS = 250L;
    // Heap is sampled on a slower cadence than the chunk counters: it is a whole-JVM number that moves
    // in GC-sized steps, and sampling it faster would only multiply the cost of reading it.
    private static final long HEAP_CHECK_INTERVAL_MS = 500L;
    // How long residency may stay over its cap before we give up waiting and generate anyway. A run must
    // never wedge: if the server genuinely will not unload (a foreign mod pinning chunks, a selection
    // whose legitimate frontier exceeds the cap), a Chunksmith that waits forever is a Chunksmith that
    // silently stopped working, which is worse than a slow one. Say so out loud and carry on.
    private static final long RESIDENCY_STALL_MAX_MS = Math.max(0L, Input.tryInteger(System.getProperty("chunksmith.residencyStallMaxMillis")).orElse(120_000));
    private final Chunksmith chunky;
    private final Selection selection;
    private final Shape shape;
    private final AtomicLong startTime = new AtomicLong();
    private final AtomicLong updateTime = new AtomicLong();
    private final AtomicLong finishedChunks = new AtomicLong();
    // Outcome counters, reported at the end of a LOD-active run. A pregen that silently does nothing
    // (or silently skips the work you asked for) is the failure mode this feature exists to kill, so a
    // run that fills LOD holes has to SAY how many it filled, and a run that does nothing has to say so.
    private final AtomicLong generatedChunks = new AtomicLong();  // chunk was absent -> generated (LOD built on the way past)
    private final AtomicLong lodOnlyChunks = new AtomicLong();    // chunk existed, LOD did not -> loaded purely to build the LOD
    private final AtomicLong skippedChunks = new AtomicLong();    // chunk + LOD both present -> no load, no write
    private final Deque<ChunkCoordinate> verifySamples = new ConcurrentLinkedDeque<>();
    private final AtomicInteger verifySampleCount = new AtomicInteger(0);
    private final AtomicLong generatedSeen = new AtomicLong();
    private final Deque<Pair<Long, AtomicLong>> updateSamples = new ConcurrentLinkedDeque<>();
    private final Progress progress;
    private final RegionCache.WorldState worldState;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger dispatchLimit = new AtomicInteger(MAX_WORKING_COUNT);
    private final AtomicLong lastThrottleNoticeTime = new AtomicLong(0);
    private final AtomicLong lastRampTime = new AtomicLong(0);
    private final AtomicLong lastBackoffTime = new AtomicLong(0);
    private final AtomicLong lastMsptCheckTime = new AtomicLong(0);
    private final boolean ioThrottleEnabled;
    private final double targetMspt;
    private final long maxChunkMillis;
    private final long maxQueuedWrites;
    private final long maxLodQueue;
    private final long resumeQueuedWrites;
    private final AtomicLong lastWriteCheckTime = new AtomicLong(0);
    private final AtomicLong lastWriteNoticeTime = new AtomicLong(0);
    private volatile boolean writeQueueStalled;
    private final AtomicLong lastWriteDrainTime = new AtomicLong(0);
    private long lastQueuedObserved = -1L;
    private final long maxAddedChunks;
    private final long maxHeapPercent;
    private final AtomicLong lastHeapCheckTime = new AtomicLong(0);
    private final AtomicLong lastHeapNoticeTime = new AtomicLong(0);
    private volatile boolean heapStalled;
    private boolean heldNotified;
    private final AtomicLong lastResidencyCheckTime = new AtomicLong(0);
    private final AtomicLong lastResidencyNoticeTime = new AtomicLong(0);
    private final AtomicLong residencyStalledSince = new AtomicLong(0);
    private volatile boolean chunkResidencyStalled;
    private volatile Thread dispatchThread;
    private ChunkIterator chunkIterator;
    private boolean stopped, cancelled;

    /**
     * The trailing settle sweep, or null when settling is off.
     *
     * <p>Holds ONE window at a time, a few chunks behind the generation front, so mods that build on new
     * land get a moment with their footprint loaded (mod_support #14). Driven from chunk completions
     * rather than from a tick, because that is the clock this class already has; at typical pregen rates
     * the hold below works out at roughly a second.
     */
    private final com.kishku7.chunksmith.util.SettleSweep settleSweep;
    private int[] settleStop;
    private int settleHeldFor;

    /** Chunk completions to keep a settle window open before moving on. */
    private static final int SETTLE_HOLD_COMPLETIONS = 60;
    private long prevTime;

    public GenerationTask(final Chunksmith chunky, final Selection selection, final long count, final long time, final boolean cancelled) {
        this(chunky, selection);
        this.chunkIterator = ChunkIteratorFactory.getChunkIterator(selection, count);
        this.finishedChunks.set(count);
        this.cancelled = cancelled;
        this.prevTime = time;
    }

    public GenerationTask(final Chunksmith chunky, final Selection selection) {
        this.chunky = chunky;
        this.selection = selection;
        this.chunkIterator = ChunkIteratorFactory.getChunkIterator(selection);
        this.shape = ShapeFactory.getShape(selection);
        this.progress = new Progress(selection.world().getName());
        this.worldState = chunky.getRegionCache().getWorld(selection.world().getName());
        this.ioThrottleEnabled = chunky.getConfig().isIoThrottleEnabled();
        this.targetMspt = chunky.getConfig().getThrottleTargetMspt();
        this.maxChunkMillis = chunky.getConfig().getThrottleMaxChunkMillis();
        this.maxQueuedWrites = chunky.getConfig().getThrottleMaxQueuedWrites();
        this.maxLodQueue = chunky.getConfig().getThrottleMaxLodQueue();
        this.maxAddedChunks = chunky.getConfig().getThrottleMaxAddedChunks();
        this.maxHeapPercent = chunky.getConfig().getThrottleMaxHeapPercent();
        AutoPause.configure(chunky.getConfig().isAutoPauseEnabled(),
                chunky.getConfig().getAutoPauseGraceSeconds() * 1000L);
        // State the settle policy for this run. A pregen is the only thing that drops chunk tickets the
        // instant generation finishes, so it is the only thing for which "hold it until the neighbours
        // exist" means anything -- see ChunkSettleWindow (mod_support #14).
        com.kishku7.chunksmith.util.ChunkSettleSupport.configure(
                chunky.getConfig().isPregenSettleEnabled(),
                chunky.getConfig().getPregenSettleDelayTicks(),
                chunky.getConfig().getPregenSettleMaxHeld());
        if (chunky.getConfig().isPregenSettleEnabled()) {
            final int radius = chunky.getConfig().getPregenSettleRadius();
            this.settleSweep = new com.kishku7.chunksmith.util.SettleSweep(
                    selection.centerChunkX() - selection.radiusChunksX(),
                    selection.centerChunkZ() - selection.radiusChunksZ(),
                    selection.diameterChunksX(), selection.diameterChunksZ(), radius);
        } else {
            this.settleSweep = null;
        }
        this.resumeQueuedWrites = this.maxQueuedWrites > 0 ? Math.max(1L, this.maxQueuedWrites / 2L) : 0L;
    }

    private synchronized void update(final int chunkX, final int chunkZ, final boolean loaded) {
        if (stopped) {
            return;
        }
        progress.chunkCount = finishedChunks.addAndGet(1);
        progress.percentComplete = 100f * progress.chunkCount / chunkIterator.total();
        final long currentTime = System.currentTimeMillis();
        final Pair<Long, AtomicLong> bin = updateSamples.peekLast();
        if (loaded) {
            worldState.setGenerated(chunkX, chunkZ);
            if (bin != null && currentTime - bin.left() < SAMPLE_SUB_INTERVAL) {
                bin.right().addAndGet(1);
            } else if (updateSamples.add(Pair.of(currentTime, new AtomicLong(1)))) {
                while (!updateSamples.isEmpty() && currentTime - updateSamples.peek().left() > SAMPLE_INTERVAL) {
                    updateSamples.poll();
                }
            }
        }
        final Pair<Long, AtomicLong> oldest = updateSamples.peek();
        final long oldestTime = oldest == null ? currentTime : oldest.left();
        final long chunksLeft = chunkIterator.total() - finishedChunks.get();
        final double timeDiff = (currentTime - oldestTime) / 1e3;
        if (chunksLeft > 0 && timeDiff < 1e-1) {
            return;
        }
        long sampleCount = 0;
        for (Pair<Long, AtomicLong> b : updateSamples) {
            sampleCount += b.right().get();
        }
        progress.rate = timeDiff > 0 ? sampleCount / timeDiff : 0;
        final long time;
        if (chunksLeft == 0) {
            time = (prevTime + (currentTime - startTime.get())) / 1000;
            progress.complete = true;
        } else {
            time = (long) (chunksLeft / progress.rate);
        }
        progress.hours = time / 3600;
        progress.minutes = (time - progress.hours * 3600) / 60;
        progress.seconds = time - progress.hours * 3600 - progress.minutes * 60;
        progress.chunkX = chunkX;
        progress.chunkZ = chunkZ;
        progress.dispatchCurrent = dispatchLimit.get();
        progress.dispatchMax = MAX_WORKING_COUNT;
        chunky.getEventBus().call(new GenerationProgressEvent(progress.world, progress.chunkCount, progress.complete, progress.percentComplete, progress.hours, progress.minutes, progress.seconds, progress.rate, progress.chunkX, progress.chunkZ));
        if (progress.complete) {
            progress.sendUpdate(chunky.getServer().getConsole());
            chunky.getEventBus().call(new GenerationTaskUpdateEvent(this));
            return;
        }
        final boolean silentMode = chunky.getConfig().isSilent();
        final boolean updateIntervalElapsed = ((currentTime - updateTime.get()) / 1e3) > chunky.getConfig().getUpdateInterval();
        if (updateIntervalElapsed) {
            if (!silentMode) {
                progress.sendUpdate(chunky.getServer().getConsole());
            }
            chunky.getEventBus().call(new GenerationTaskUpdateEvent(this));
            updateTime.set(currentTime);
        }
    }

    /**
     * Primary throttle signal. Evaluated on a fixed cadence (even while completions are
     * stalled) so concurrency can fall under sustained load. When the server main thread
     * falls behind its tick budget we back off; when it is comfortably keeping up we ramp
     * back up. On platforms that cannot report tick time this is a no-op and the per-chunk
     * latency backstop carries the throttle instead.
     */
    private void adjustFromTickHealth() {
        final long now = System.currentTimeMillis();
        final long last = lastMsptCheckTime.get();
        if (now - last < MSPT_CHECK_INTERVAL_MS || !lastMsptCheckTime.compareAndSet(last, now)) {
            return;
        }
        final double mspt = chunky.getServer().getMillisPerTick();
        if (mspt < 0.0D) {
            return;
        }
        if (mspt > targetMspt + MSPT_BAND) {
            backoff();
        } else if (mspt < targetMspt - MSPT_BAND) {
            rampUp();
        }
    }

    /**
     * Backstop signal. A single chunk load exceeding the absolute latency cap means the
     * disk is stalling regardless of what the tick average has done; back off immediately.
     */
    private void adjustFromChunkLatency(final long elapsed) {
        if (elapsed > maxChunkMillis) {
            backoff();
        }
    }

    /**
     * LOD-sink governor. The LOD sink (voxy) queues ingest work on an UNBOUNDED queue and never
     * reports saturation, so it cannot push back on us -- we have to watch it. When its backlog
     * exceeds the configured bound, back off dispatch until it drains.
     */
    private void adjustFromLodQueue() {
        if (maxLodQueue <= 0L) {
            return;
        }
        if (LodSinks.get().queueDepth() > maxLodQueue) {
            backoff();
        }
    }

    private void backoff() {
        final long now = System.currentTimeMillis();
        final long last = lastBackoffTime.get();
        if (now - last < BACKOFF_INTERVAL_MS || !lastBackoffTime.compareAndSet(last, now)) {
            return;
        }
        int current;
        do {
            current = dispatchLimit.get();
            if (current <= 1) {
                return;
            }
        } while (!dispatchLimit.compareAndSet(current, current - 1));
        // Hold off ramping briefly so a single back-off isn't immediately undone.
        lastRampTime.set(now);
        maybeNotify(current - 1);
    }

    private void rampUp() {
        final long now = System.currentTimeMillis();
        final long last = lastRampTime.get();
        if (now - last < RAMP_INTERVAL_MS || !lastRampTime.compareAndSet(last, now)) {
            return;
        }
        int current;
        do {
            current = dispatchLimit.get();
            if (current >= MAX_WORKING_COUNT) {
                return;
            }
        } while (!dispatchLimit.compareAndSet(current, current + 1));
        maybeNotify(current + 1);
    }

    private void maybeNotify(final int newLimit) {
        final long now = System.currentTimeMillis();
        final long last = lastThrottleNoticeTime.get();
        if (now - last >= NOTICE_INTERVAL_MS && lastThrottleNoticeTime.compareAndSet(last, now)) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_THROTTLE_NOTICE, newLimit, MAX_WORKING_COUNT);
        }
    }

    /**
     * Write-queue backpressure. Tick-health and per-chunk latency react to the server thread
     * and to load completion, but the deferred region-write backlog can still grow unbounded
     * when chunks finish generating faster than the disk can flush them (async I/O does not
     * raise tick time). This samples that backlog on a fixed cadence and, once it exceeds the
     * configured cap, holds off all new dispatches until it drains back below half the cap
     * (hysteresis) -- directly bounding the unflushed-write window so a slow disk paces
     * generation instead of being buried.
     * <p>
     * Absolute depth alone, though, misses the worst case: a single-threaded region writer
     * blocked in fsync during region-file eviction keeps the queue <em>shallow</em> while each
     * flush blocks for seconds, so depth never reaches the cap even as the disk pegs and an
     * autosave {@code synchronize()} on the main thread eventually overruns the watchdog. So it
     * also trips on <em>drain-stall</em>: queued work that shows no progress for
     * {@code WRITE_STALL_MILLIS}. No-op when the platform cannot report the depth.
     */
    private void evaluateWriteBackpressure() {
        final long now = System.currentTimeMillis();
        final long last = lastWriteCheckTime.get();
        if (now - last < WRITE_CHECK_INTERVAL_MS || !lastWriteCheckTime.compareAndSet(last, now)) {
            return;
        }
        final long queued = selection.world().getQueuedChunkWrites();
        if (queued < 0) {
            writeQueueStalled = false;
            lastQueuedObserved = -1L;
            return;
        }
        // Track drain progress. The region writer pops one chunk at a time, so any decrease in
        // depth means the disk is flushing; an empty queue is fully drained. When the queue
        // holds work but stops shrinking, the writer is blocked in fsync.
        final long prev = lastQueuedObserved;
        lastQueuedObserved = queued;
        if (queued <= 0L || prev < 0L || queued < prev) {
            lastWriteDrainTime.set(now);
        }
        final long sinceDrain = now - lastWriteDrainTime.get();
        if (writeQueueStalled) {
            final boolean resume;
            if (WRITE_STALL_MILLIS <= 0L) {
                resume = queued <= resumeQueuedWrites;
            } else {
                resume = queued <= 0L
                        || (queued <= resumeQueuedWrites && sinceDrain < WRITE_STALL_MILLIS);
            }
            if (resume) {
                writeQueueStalled = false;
            }
        } else {
            final boolean depthExceeded = maxQueuedWrites > 0L && queued >= maxQueuedWrites;
            final boolean drainStalled = WRITE_STALL_MILLIS > 0L
                    && queued >= WRITE_STALL_MIN_DEPTH
                    && sinceDrain >= WRITE_STALL_MILLIS;
            if (depthExceeded || drainStalled) {
                writeQueueStalled = true;
                maybeNotifyWrite(queued);
            }
        }
    }

    /**
     * Chunk-residency backpressure -- the bound on what is already IN memory.
     *
     * <p>Every other signal here measures the rate work arrives: tick time, per-chunk latency, the
     * write queue, the LOD sink. None of them can see the resident chunk set, and on a real server
     * (2026-08-19) that set reached 75,045 holders -- about ten times the sweep frontier the run needed
     * -- while every one of those signals read "slow down", which is precisely what stopped it draining.
     *
     * <p>The loop is the problem, not the number: vanilla's unload pass is budgeted by the server's own
     * per-tick time allowance, so a server that has fallen behind unloads almost nothing; a bigger
     * resident set costs more to tick; it falls further behind. Backing dispatch off does not break that
     * -- it feeds it, because a settle window's releases are driven by new arrivals. So residency gets a
     * signal of its own and a HARD gate: past the cap, dispatch nothing at all until the server has
     * unloaded back to half of it. That is the one action that actually lets the loop unwind.
     *
     * <p>No-op when the platform does not report residency, and when the operator has set the cap to 0.
     */
    private void evaluateChunkResidency() {
        if (maxAddedChunks <= 0L) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long last = lastResidencyCheckTime.get();
        if (now - last < RESIDENCY_CHECK_INTERVAL_MS || !lastResidencyCheckTime.compareAndSet(last, now)) {
            return;
        }
        // The DELTA, not the absolute count. 3.5.0 gated on "how many chunks exist", which on a server
        // whose ordinary resident set was already near the cap meant the gate closed on somebody else's
        // chunks and never opened -- measured on a live server 2026-08-20, where it stuttered a run at
        // the never-wedge interval. What a pregen can be held responsible for is what it ADDED.
        final long added = ChunkResidency.addedChunks();
        if (added < 0L) {
            // The platform is not reporting, the reading went stale, or we never got a baseline.
            // Unknown is not zero: leave the gate exactly as it was rather than opening it on the
            // strength of a measurement we do not have. It cannot latch shut -- the deadline applies.
            return;
        }
        if (chunkResidencyStalled) {
            if (added <= Math.max(1L, maxAddedChunks / 2L)) {
                chunkResidencyStalled = false;
                residencyStalledSince.set(0L);
                return;
            }
            final long since = residencyStalledSince.get();
            if (RESIDENCY_STALL_MAX_MS > 0L && since > 0L && now - since >= RESIDENCY_STALL_MAX_MS) {
                chunkResidencyStalled = false;
                residencyStalledSince.set(0L);
                // Reset the notice clock too, so the give-up line is never swallowed by the throttle
                // notice that preceded it.
                lastResidencyNoticeTime.set(0L);
                chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_RESIDENCY_STUCK_NOTICE,
                        maxAddedChunks, (now - since) / 1000L);
            }
            return;
        }
        if (added >= maxAddedChunks) {
            chunkResidencyStalled = true;
            residencyStalledSince.set(now);
            maybeNotifyResidency(added);
        }
    }

    /**
     * The backstop the chunk counters could not be.
     *
     * <p>Three releases of this mod have tried to bound a pregen by counting proxies -- queued writes,
     * LOD queue depth, resident chunks, chunks added since the run started -- and each has been wrong
     * on a real server in a different way. A chunk is worth wildly different amounts of heap depending
     * on the entities and block entities that came with it, so no chunk count can be tuned to mean the
     * same thing on two worlds. What actually ends a pregen badly is running out of memory. Measure
     * that instead, and let the counters handle the cases they are genuinely good at.
     */
    private void evaluateHeapPressure() {
        if (maxHeapPercent <= 0L) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long last = lastHeapCheckTime.get();
        if (now - last < HEAP_CHECK_INTERVAL_MS || !lastHeapCheckTime.compareAndSet(last, now)) {
            return;
        }
        final boolean hold = HeapPressure.shouldHold(heapStalled, maxHeapPercent);
        if (hold && !heapStalled) {
            maybeNotifyHeap();
        }
        heapStalled = hold;
    }

    private void maybeNotifyHeap() {
        final long now = System.currentTimeMillis();
        final long last = lastHeapNoticeTime.get();
        if (now - last >= NOTICE_INTERVAL_MS && lastHeapNoticeTime.compareAndSet(last, now)) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_HEAP_BACKPRESSURE_NOTICE,
                    String.format("%.0f", HeapPressure.usedPercent()),
                    HeapPressure.usedMegabytes(), HeapPressure.maxMegabytes(), maxHeapPercent);
        }
    }

    private void maybeNotifyResidency(final long added) {
        final long now = System.currentTimeMillis();
        final long last = lastResidencyNoticeTime.get();
        if (now - last >= NOTICE_INTERVAL_MS && lastResidencyNoticeTime.compareAndSet(last, now)) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_RESIDENCY_BACKPRESSURE_NOTICE,
                    added, maxAddedChunks, ChunkResidency.loadedChunks());
        }
    }

    private void maybeNotifyWrite(final long queued) {
        final long now = System.currentTimeMillis();
        final long last = lastWriteNoticeTime.get();
        if (now - last >= NOTICE_INTERVAL_MS && lastWriteNoticeTime.compareAndSet(last, now)) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_WRITE_BACKPRESSURE_NOTICE, queued, maxQueuedWrites);
        }
    }

    @Override
    public void run() {
        final String poolThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName(String.format("Chunksmith-%s Thread", selection.world().getName()));
        dispatchThread = Thread.currentThread();
        if (!chunkIterator.process()) {
            stop(true);
        }
        final boolean forceLoadExistingChunks = chunky.getConfig().isForceLoadExistingChunks();
        // The CSLOD store is a first-class part of the skip decision, but ONLY when LOD generation is
        // actually active for this world. Null means it is not, and null takes the original code path
        // untouched. Null happens in two ways, both of which must behave exactly as they did before:
        //   - a plugin cell (Bukkit/Paper/Folia): there is no LOD pipeline there at all, so nothing
        //     ever publishes a provider and this is unconditionally null;
        //   - a loader cell whose lodEnabled tristate resolved to OFF.
        // forceLoadExistingChunks keeps its old meaning as the explicit override -- reprocess every
        // chunk in the selection regardless, LOD present or not -- so there is nothing for the index
        // to decide and we do not even build one.
        final CsLodPresenceIndex lodIndex = forceLoadExistingChunks
                ? null
                : LodPresence.indexFor(selection.world().getName());
        // The index outlives the task (it is cached per dimension for the server's lifetime), so its
        // counters are cumulative. Snapshot them here and report the DELTA, or the summary would bill
        // this run for every earlier run's work too.
        final CsLodPresenceIndex.Cost lodCostBefore = lodIndex == null ? null : lodIndex.cost();
        // Everything already resident belongs to the server, not to this run. Capture it before the
        // first dispatch so the gate below measures OUR growth and nothing else's.
        ChunkResidency.noteTaskStart();
        HeapPressure.reset();
        com.kishku7.chunksmith.util.TicketLedger.reset();
        startTime.set(System.currentTimeMillis());
        while (!stopped && chunkIterator.hasNext()) {
            final ChunkCoordinate chunk = chunkIterator.next();
            final int chunkCenterX = (chunk.x() << 4) + 8;
            final int chunkCenterZ = (chunk.z() << 4) + 8;
            if (!shape.isBounding(chunkCenterX, chunkCenterZ)) {
                update(chunk.x(), chunk.z(), false);
                continue;
            }
            // The rule, in order:
            //   no chunk       -> generate it; the load hook builds the LOD on the way past
            //   chunk, no LOD  -> LOAD it (no worldgen) purely so the load hook builds the LOD
            //   chunk + LOD    -> nothing, next
            boolean lodBackfill = false;
            if (!forceLoadExistingChunks && worldState.isGenerated(chunk.x(), chunk.z())) {
                if (lodIndex == null || lodIndex.hasLod(chunk.x(), chunk.z())) {
                    skippedChunks.incrementAndGet();
                    update(chunk.x(), chunk.z(), false);
                    continue;
                }
                // Chunk on disk, no CSLOD record. Fall through to the dispatch below with the
                // generated-check FORCED to "not generated". That sends it down getChunkAtAsync, where
                // the chunk system reads the existing FULL chunk off disk -- no worldgen runs for a
                // chunk that is already complete -- and the platform's load hook offers the live chunk
                // to LodSupport, which extracts and writes the LOD. This is the hole-filling path, and
                // it reuses the existing hook exactly: no worldgen behaviour is touched.
                lodBackfill = true;
            }
            final boolean lodOnly = lodBackfill;
            // Wait for a dispatch slot -- park 1ms at a time so stop() is responsive.
            // Re-evaluate tick health each pass so the limit can drop even while saturated
            // (no slot free) and chunk-completion callbacks have stopped firing.
            // Math.max(1, ...) guards against dispatchLimit ever hitting 0.
            while (!stopped) {
                if (ioThrottleEnabled) {
                    adjustFromTickHealth();
                    if (maxQueuedWrites > 0) {
                        evaluateWriteBackpressure();
                    }
                }
                // The LOD sink is governed regardless of ioThrottleEnabled: it is not a disk-I/O
                // signal, and an unbounded ingest backlog is an OOM, not a slowdown. Chunk residency is
                // governed for the same reason -- an unbounded resident set is an OOM and a watchdog
                // kill, not a slowdown -- so neither is gated on the operator's I/O-throttle switch.
                adjustFromLodQueue();
                evaluateChunkResidency();
                evaluateHeapPressure();
                final boolean gated = writeQueueStalled || chunkResidencyStalled || heapStalled;
                final long gateNow = System.currentTimeMillis();
                AutoPause.noteGated(gated, gateNow);
                if (AutoPause.shouldPause(gateNow)) {
                    // Stuttering is worse than stopping: on a server that cannot keep up, the
                    // never-wedge valve lets through about a second of work every grace period and
                    // nothing useful gets generated, while the server stays under load throughout.
                    // Stop, say why, and let the resume watcher restart it when the pressure lifts.
                    chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_AUTO_PAUSED,
                            selection.world().getName(), AutoPause.gatedSeconds(gateNow));
                    AutoPause.markAutoPaused(selection.world().getName());
                    stop(false);
                    break;
                }
                if (gated != heldNotified) {
                    heldNotified = gated;
                    ChunkResidency.noteGenerationHeld(gated);
                    if (gated) {
                        // The frontier cannot complete while dispatch is stopped, so it would sit at
                        // its cap holding tickets and prevent the unloading this gate is waiting for.
                        com.kishku7.chunksmith.util.ChunkSettleSupport.flushAll();
                    }
                }
                if (!gated && inFlight.get() < Math.max(1, dispatchLimit.get())) {
                    break;
                }
                LockSupport.parkNanos(1_000_000L);
            }
            if (stopped) {
                break;
            }
            inFlight.incrementAndGet();
            final long dispatchTime = System.currentTimeMillis();
            // A LOD backfill forces the load: the chunk IS generated, and saying so here would send it
            // straight back down the skip branch -- which is precisely the bug (an already-generated
            // chunk was never loaded, so the LOD hook never saw it).
            final CompletableFuture<Boolean> isChunkGenerated = (forceLoadExistingChunks || lodOnly) ?
                    CompletableFuture.completedFuture(false) :
                    selection.world().isChunkGenerated(chunk.x(), chunk.z());
            isChunkGenerated
                    .thenCompose(generated -> {
                        if (Boolean.TRUE.equals(generated)) {
                            // The chunk IS on disk. This is the gate that actually decides on a freshly
                            // booted server: worldState above is an in-memory RegionCache that starts
                            // COLD, so on the re-run that matters -- server restarted, world already
                            // pregenerated -- every chunk arrives here, not at the cache check. The LOD
                            // decision therefore has to be made HERE too. Making it only against the
                            // cache is the bug: it silently skips the entire selection and builds no
                            // LODs at all.
                            if (lodIndex != null && !lodIndex.hasLod(chunk.x(), chunk.z())) {
                                // Chunk, no LOD -> load it (no worldgen: it is already complete) purely
                                // so the platform's load hook extracts and writes the LOD.
                                lodIndex.markLod(chunk.x(), chunk.z());
                                lodOnlyChunks.incrementAndGet();
                                return selection.world().getChunkAtAsync(chunk.x(), chunk.z());
                            }
                            // Chunk + LOD (or LOD off): nothing to do, and nothing gets loaded.
                            skippedChunks.incrementAndGet();
                            return CompletableFuture.completedFuture(null);
                        }
                        // We are about to load the chunk, which is what fires the LOD hook. Claim it
                        // NOW rather than when the store's writer thread lands it: the write is async,
                        // so the on-disk header lags dispatch, and only the in-memory bitmap can stop
                        // this same run from re-processing the chunk.
                        if (lodIndex != null) {
                            lodIndex.markLod(chunk.x(), chunk.z());
                        }
                        if (lodOnly) {
                            lodOnlyChunks.incrementAndGet();
                        } else {
                            generatedChunks.incrementAndGet();
                            noteVerifySample(chunk.x(), chunk.z());
                        }
                        return selection.world().getChunkAtAsync(chunk.x(), chunk.z());
                    }).whenComplete((ignored, throwable) -> {
                        final long elapsed = System.currentTimeMillis() - dispatchTime;
                        inFlight.decrementAndGet();
                        if (ioThrottleEnabled) {
                            adjustFromChunkLatency(elapsed);
                        }
                        LockSupport.unpark(dispatchThread);
                        update(chunk.x(), chunk.z(), true);
                        driveSettleSweep(chunk.x(), chunk.z());
                    });
        }
        // Say what actually happened. A run that filled LOD holes must report how many it filled, and a
        // run that found nothing to do must say so rather than just going quiet -- the counts are the
        // whole point. Only emitted when LOD was active, so a non-LOD run's output is unchanged.
        // The final dispatches are still in flight here, and their outcomes are not counted until
        // they land -- without this wait the summary silently under-reports by up to the dispatch
        // limit, and the verification below would sample chunks that have not landed yet. Bounded,
        // so a wedged chunk can never hang the task's completion. Runs for every task, not just LOD
        // ones: a task should not declare itself finished while its own work is still outstanding.
        final long drainDeadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (inFlight.get() > 0 && System.currentTimeMillis() < drainDeadline) {
            LockSupport.parkNanos(1_000_000L);
        }
        if (lodIndex != null) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_LOD_SUMMARY,
                    selection.world().getName(),
                    generatedChunks.get(),
                    lodOnlyChunks.get(),
                    skippedChunks.get(),
                    lodIndex.describeCostSince(lodCostBefore));
        }
        if (!stopped) {
            verifyGeneratedSample();
        }
        if (stopped) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_STOPPED, selection.world().getName());
        } else {
            cancelled = true;
        }
        chunky.getTaskLoader().saveTask(this);
        chunky.getGenerationTasks().remove(selection.world().getName());
        Thread.currentThread().setName(poolThreadName);
        // Nothing more is coming, so the chunks on the edge of the run will never have their
        // neighbourhood completed. Hand every held ticket back before anyone is told the task is done.
        ChunkResidency.noteGenerationHeld(false);
        finishSettleSweep();
        selection.world().settleDrain();
        // Ending a task is NOT the same as finishing the work. The tickets come back now; the chunks do
        // not go away until the distance manager has propagated that and the unload pass has run. 3.5.0
        // stopped driving the unload pass the moment a task ended, which orphaned the backlog -- 39,064
        // chunks were still resident nineteen minutes after a pregen stopped, with the server idle and
        // no players on, and it stayed that way until a restart. Declare the debt here; the platform's
        // tick hook keeps paying it until residency is actually back down.
        ChunkResidency.noteTaskEnd();
        chunky.getEventBus().call(new GenerationTaskFinishEvent(this));
        chunky.getEventBus().call(new GenerationCompleteEvent(selection.world().getName()));
    }

    // A chunk counted as "generated" is only a CLAIM. The counter is incremented when the load
    // future completes, and a future can complete having done nothing at all: mod_support #13 was
    // exactly that -- getChunkFutureMainThread handed back an already-completed FAILED ChunkResult,
    // which is not an exception, so the completion callback saw no error and counted the chunk.
    // Every counter and log line reported success while the world stayed empty.
    //
    // So do not trust the counter: take a spread of the chunks this run says it generated and ask
    // the world whether they are actually there. At most VERIFY_SAMPLE_MAX status reads, once, at
    // the end of a run -- negligible next to the generation itself, and it makes this whole class
    // of silent failure impossible to ship again.
    private void noteVerifySample(final int x, final int z) {
        if (verifySampleCount.get() >= VERIFY_SAMPLE_MAX) {
            return;
        }
        final long seen = generatedSeen.incrementAndGet();
        // Spread the sample across the selection rather than taking the first N, so a run that
        // breaks partway through is still caught.
        final long stride = Math.max(1L, chunkIterator.total() / VERIFY_SAMPLE_MAX);
        if (seen % stride == 0L && verifySampleCount.incrementAndGet() <= VERIFY_SAMPLE_MAX) {
            verifySamples.add(new ChunkCoordinate(x, z));
        }
    }

    private void verifyGeneratedSample() {
        if (verifySamples.isEmpty()) {
            return;
        }
        // Let the writes land first. The chunk is generated the moment the load future completes,
        // but it reaches DISK only when the write queue drains -- and asking the world whether a
        // chunk is on disk while its write is still queued reports a perfectly good chunk as
        // missing. Bounded, and skipped entirely where the gauge is unavailable (-1 on Bukkit and
        // on replaced chunk systems we cannot read).
        final long drainUntil = System.currentTimeMillis() + VERIFY_WRITE_DRAIN_MS;
        while (System.currentTimeMillis() < drainUntil) {
            final long queued = selection.world().getQueuedChunkWrites();
            if (queued <= 0L) {
                break;
            }
            LockSupport.parkNanos(50_000_000L);
        }
        final List<ChunkCoordinate> missing = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        int checked = 0;
        for (final ChunkCoordinate coordinate : verifySamples) {
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                break;
            }
            try {
                final Boolean present = selection.world()
                        .isChunkGenerated(coordinate.x(), coordinate.z())
                        .get(remaining, TimeUnit.MILLISECONDS);
                checked++;
                if (!Boolean.TRUE.equals(present)) {
                    missing.add(coordinate);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (final Exception e) {
                // Timed out, or the world could not answer -- a busy or shutting-down server, not
                // evidence of anything. Stay quiet: a check that cries wolf gets ignored, which is
                // worse than no check at all. Only a definite "not on disk" is reported.
                break;
            }
        }
        // Decisive or silent.
        if (missing.size() < VERIFY_MIN_MISSING || missing.size() < checked * VERIFY_MISSING_FRACTION) {
            return;
        }
        final StringBuilder examples = new StringBuilder();
        for (int i = 0; i < Math.min(3, missing.size()); i++) {
            if (i > 0) {
                examples.append("; ");
            }
            examples.append(missing.get(i).x()).append(",").append(missing.get(i).z());
        }
        chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_VERIFY_FAILED,
                selection.world().getName(), missing.size(), checked, examples.toString());
    }

    /**
     * Advance the trailing settle window as chunks land.
     *
     * <p>One window at a time: hold it for a while so the server can tick with that ground loaded, then
     * let it go and take the next eligible stop. Eligibility is the sweep's business -- it only offers a
     * window whose chunks are all already generated, so nothing here can trigger worldgen.
     */
    private synchronized void driveSettleSweep(final int chunkX, final int chunkZ) {
        if (settleSweep == null || stopped) {
            return;
        }
        settleSweep.markGenerated(chunkX, chunkZ);
        if (settleStop != null) {
            if (++settleHeldFor < SETTLE_HOLD_COMPLETIONS) {
                return;
            }
            selection.world().settleRelease(settleStop[0], settleStop[1], settleSweep.radius());
            settleStop = null;
        }
        final int[] next = settleSweep.nextStop();
        if (next != null) {
            settleStop = next;
            settleHeldFor = 0;
            selection.world().settleLoad(next[0], next[1], settleSweep.radius());
        }
    }

    /**
     * Sweep whatever the trailing pass could not reach while generation was still running.
     *
     * <p>The last band is only eligible once the final chunks land, so it is necessarily swept here. It
     * is bounded by the width of that band, not by the size of the run -- everything behind it was
     * already settled on the way past.
     */
    private void finishSettleSweep() {
        if (settleSweep == null) {
            return;
        }
        synchronized (this) {
            if (settleStop != null) {
                selection.world().settleRelease(settleStop[0], settleStop[1], settleSweep.radius());
                settleStop = null;
            }
        }
        int[] stop;
        while (!cancelled && (stop = settleSweep.nextStop()) != null) {
            selection.world().settleLoad(stop[0], stop[1], settleSweep.radius());
            LockSupport.parkNanos(SETTLE_TAIL_HOLD_NANOS);
            selection.world().settleRelease(stop[0], stop[1], settleSweep.radius());
        }
    }

    /** How long the tail pass keeps each window. A tick is 50 ms; this is about a second. */
    private static final long SETTLE_TAIL_HOLD_NANOS = 1_000_000_000L;

    public void stop(final boolean cancelled) {
        this.stopped = true;
        this.cancelled = cancelled;
    }

    public Chunksmith getChunky() {
        return chunky;
    }

    public Selection getSelection() {
        return selection;
    }

    public long getCount() {
        return finishedChunks.get();
    }

    /** Chunks that did not exist and were generated (their LOD is built by the load hook on the way past). */
    public long getGeneratedChunks() {
        return generatedChunks.get();
    }

    /** Chunks that already existed but had no CSLOD record, so were loaded purely to build the LOD. */
    public long getLodOnlyChunks() {
        return lodOnlyChunks.get();
    }

    /** Chunks skipped outright -- already generated, and (when LOD is on) already carrying a LOD. */
    public long getSkippedChunks() {
        return skippedChunks.get();
    }

    public ChunkIterator getChunkIterator() {
        return chunkIterator;
    }

    public Shape getShape() {
        return shape;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public long getTotalTime() {
        return prevTime + (startTime.get() > 0 ? System.currentTimeMillis() - startTime.get() : 0);
    }

    public Progress getProgress() {
        return progress;
    }

    @SuppressWarnings("unused")
    public static final class Progress {
        private static final long THROTTLE_STATUS_INTERVAL_MS = 5_000L;
        private final String world;
        private long chunkCount;
        private boolean complete;
        private float percentComplete;
        private long hours, minutes, seconds;
        private double rate;
        private int chunkX, chunkZ;
        private int dispatchCurrent;
        private int dispatchMax;
        private long lastThrottleStatusPrintMs = 0;

        private Progress(final String world) {
            this.world = world;
        }

        public String getWorld() {
            return world;
        }

        public long getChunkCount() {
            return chunkCount;
        }

        public boolean isComplete() {
            return complete;
        }

        public float getPercentComplete() {
            return percentComplete;
        }

        public long getHours() {
            return hours;
        }

        public long getMinutes() {
            return minutes;
        }

        public long getSeconds() {
            return seconds;
        }

        public double getRate() {
            return rate;
        }

        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }

        public int getDispatchCurrent() {
            return dispatchCurrent;
        }

        public int getDispatchMax() {
            return dispatchMax;
        }

        public void sendUpdate(final Sender sender) {
            if (complete) {
                sender.sendMessagePrefixed(TranslationKey.TASK_DONE, world, chunkCount, String.format("%.2f", percentComplete), String.format("%01d", hours), String.format("%02d", minutes), String.format("%02d", seconds));
            } else {
                sender.sendMessagePrefixed(TranslationKey.TASK_UPDATE, world, chunkCount, String.format("%.2f", percentComplete), String.format("%01d", hours), String.format("%02d", minutes), String.format("%02d", seconds), String.format("%.1f", rate), chunkX, chunkZ);
                if (dispatchCurrent < dispatchMax) {
                    final long now = System.currentTimeMillis();
                    if (now - lastThrottleStatusPrintMs >= THROTTLE_STATUS_INTERVAL_MS) {
                        lastThrottleStatusPrintMs = now;
                        sender.sendMessagePrefixed(TranslationKey.TASK_THROTTLE_STATUS, dispatchCurrent, dispatchMax);
                    }
                }
            }
        }
    }
}
