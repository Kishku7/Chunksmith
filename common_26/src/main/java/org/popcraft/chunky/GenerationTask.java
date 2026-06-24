package org.popcraft.chunky;

import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;
import org.popcraft.chunky.event.task.GenerationTaskFinishEvent;
import org.popcraft.chunky.event.task.GenerationTaskUpdateEvent;
import org.popcraft.chunky.iterator.ChunkIterator;
import org.popcraft.chunky.iterator.ChunkIteratorFactory;
import org.popcraft.chunky.platform.Sender;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.shape.ShapeFactory;
import org.popcraft.chunky.util.ChunkCoordinate;
import org.popcraft.chunky.util.Input;
import org.popcraft.chunky.util.Pair;
import org.popcraft.chunky.util.RegionCache;
import org.popcraft.chunky.util.TranslationKey;

import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class GenerationTask implements Runnable {
    private static final int MAX_WORKING_COUNT = Input.tryInteger(System.getProperty("chunky.maxWorkingCount")).orElse(50);
    private static final double SAMPLE_INTERVAL = 1000d * Math.max(Input.tryInteger(System.getProperty("chunky.sampleInterval")).orElse(30), 30);
    private static final double SAMPLE_SUB_INTERVAL = SAMPLE_INTERVAL / 30;
    private static final long NOTICE_INTERVAL_MS = 10_000L;
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
    private static final long WRITE_STALL_MILLIS = Math.max(0L, Input.tryInteger(System.getProperty("chunky.writeStallMillis")).orElse(2000));
    private static final long WRITE_STALL_MIN_DEPTH = Math.max(1L, Input.tryInteger(System.getProperty("chunky.writeStallMinDepth")).orElse(16));
    private final Chunky chunky;
    private final Selection selection;
    private final Shape shape;
    private final AtomicLong startTime = new AtomicLong();
    private final AtomicLong updateTime = new AtomicLong();
    private final AtomicLong finishedChunks = new AtomicLong();
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
    private final long resumeQueuedWrites;
    private final AtomicLong lastWriteCheckTime = new AtomicLong(0);
    private final AtomicLong lastWriteNoticeTime = new AtomicLong(0);
    private volatile boolean writeQueueStalled;
    private final AtomicLong lastWriteDrainTime = new AtomicLong(0);
    private long lastQueuedObserved = -1L;
    private volatile Thread dispatchThread;
    private ChunkIterator chunkIterator;
    private boolean stopped, cancelled;
    private long prevTime;

    public GenerationTask(final Chunky chunky, final Selection selection, final long count, final long time, final boolean cancelled) {
        this(chunky, selection);
        this.chunkIterator = ChunkIteratorFactory.getChunkIterator(selection, count);
        this.finishedChunks.set(count);
        this.cancelled = cancelled;
        this.prevTime = time;
    }

    public GenerationTask(final Chunky chunky, final Selection selection) {
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
        startTime.set(System.currentTimeMillis());
        while (!stopped && chunkIterator.hasNext()) {
            final ChunkCoordinate chunk = chunkIterator.next();
            final int chunkCenterX = (chunk.x() << 4) + 8;
            final int chunkCenterZ = (chunk.z() << 4) + 8;
            if (!shape.isBounding(chunkCenterX, chunkCenterZ)) {
                update(chunk.x(), chunk.z(), false);
                continue;
            }
            if (!forceLoadExistingChunks && worldState.isGenerated(chunk.x(), chunk.z())) {
                update(chunk.x(), chunk.z(), false);
                continue;
            }
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
                if (!writeQueueStalled && inFlight.get() < Math.max(1, dispatchLimit.get())) {
                    break;
                }
                LockSupport.parkNanos(1_000_000L);
            }
            if (stopped) {
                break;
            }
            inFlight.incrementAndGet();
            final long dispatchTime = System.currentTimeMillis();
            final CompletableFuture<Boolean> isChunkGenerated = forceLoadExistingChunks ?
                    CompletableFuture.completedFuture(false) :
                    selection.world().isChunkGenerated(chunk.x(), chunk.z());
            isChunkGenerated
                    .thenCompose(generated -> {
                        if (Boolean.TRUE.equals(generated)) {
                            return CompletableFuture.completedFuture(null);
                        } else {
                            return selection.world().getChunkAtAsync(chunk.x(), chunk.z());
                        }
                    }).whenComplete((ignored, throwable) -> {
                        final long elapsed = System.currentTimeMillis() - dispatchTime;
                        inFlight.decrementAndGet();
                        if (ioThrottleEnabled) {
                            adjustFromChunkLatency(elapsed);
                        }
                        LockSupport.unpark(dispatchThread);
                        update(chunk.x(), chunk.z(), true);
                    });
        }
        if (stopped) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_STOPPED, selection.world().getName());
        } else {
            cancelled = true;
        }
        chunky.getTaskLoader().saveTask(this);
        chunky.getGenerationTasks().remove(selection.world().getName());
        Thread.currentThread().setName(poolThreadName);
        chunky.getEventBus().call(new GenerationTaskFinishEvent(this));
        chunky.getEventBus().call(new GenerationCompleteEvent(selection.world().getName()));
    }

    public void stop(final boolean cancelled) {
        this.stopped = true;
        this.cancelled = cancelled;
    }

    public Chunky getChunky() {
        return chunky;
    }

    public Selection getSelection() {
        return selection;
    }

    public long getCount() {
        return finishedChunks.get();
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
