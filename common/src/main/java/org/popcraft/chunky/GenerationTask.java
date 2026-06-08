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
    private static final long RAMP_INTERVAL_MS = 1_000L;    // max one +1 step per second
    private static final long BACKOFF_INTERVAL_MS = 3_000L; // max one -1 step per 3 seconds
    // No throttle during warmup (before fastSampleSize samples collected) — HDD cold-start
    // latency easily exceeds any fixed constant, so wait for a real baseline first.
    private static final long FALLBACK_THRESHOLD_MS = Long.MAX_VALUE / 2;
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
    private final ConcurrentLinkedDeque<Long> fastSamples = new ConcurrentLinkedDeque<>();
    private final AtomicLong lastThrottleNoticeTime = new AtomicLong(0);
    private final AtomicLong lastRampTime = new AtomicLong(0);
    private final AtomicLong lastBackoffTime = new AtomicLong(0);
    private final boolean ioThrottleEnabled;
    private final double slowMultiplier;
    private final int fastSampleSize;
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
        this.slowMultiplier = chunky.getConfig().getSlowMultiplier();
        this.fastSampleSize = chunky.getConfig().getFastSampleSize();
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

    private void adjustThrottle(final long elapsed) {
        final long threshold = computeThreshold();
        if (elapsed > threshold) {
            // Back off by 1, rate-limited to BACKOFF_INTERVAL_MS. This prevents
            // individual HDD seek outliers from thrashing the limit while still
            // allowing sustained saturation to drive concurrency down steadily.
            final long now = System.currentTimeMillis();
            final long last = lastBackoffTime.get();
            if (now - last >= BACKOFF_INTERVAL_MS && lastBackoffTime.compareAndSet(last, now)) {
                int current;
                do {
                    current = dispatchLimit.get();
                    if (current <= 1) return;
                } while (!dispatchLimit.compareAndSet(current, current - 1));
                maybeNotify(current - 1);
            }
        } else {
            updateFastBaseline(elapsed);
            // Ramp up at most +1 per RAMP_INTERVAL_MS. Many whenComplete callbacks can
            // fire in the same millisecond at high concurrency; the CAS on lastRampTime
            // ensures only one thread wins the increment per interval.
            final long now = System.currentTimeMillis();
            final long last = lastRampTime.get();
            if (now - last >= RAMP_INTERVAL_MS && lastRampTime.compareAndSet(last, now)) {
                int current;
                do {
                    current = dispatchLimit.get();
                    if (current >= MAX_WORKING_COUNT) {
                        return;
                    }
                } while (!dispatchLimit.compareAndSet(current, current + 1));
                maybeNotify(current + 1);
            }
        }
    }

    private long computeThreshold() {
        final Object[] samples = fastSamples.toArray();
        if (samples.length < fastSampleSize) {
            return FALLBACK_THRESHOLD_MS;
        }
        long sum = 0;
        for (final Object s : samples) {
            sum += (Long) s;
        }
        return (long) ((sum / (double) samples.length) * slowMultiplier);
    }

    private void updateFastBaseline(final long elapsed) {
        // Collect samples at any concurrency level so the threshold adapts to the
        // current operating point rather than being anchored to max-concurrency
        // performance. This prevents false throttling when individual chunk times
        // at low concurrency are naturally higher than the max-concurrency baseline.
        fastSamples.addLast(elapsed);
        while (fastSamples.size() > fastSampleSize) {
            fastSamples.pollFirst();
        }
    }

    private void maybeNotify(final int newLimit) {
        final long now = System.currentTimeMillis();
        final long last = lastThrottleNoticeTime.get();
        if (now - last >= NOTICE_INTERVAL_MS && lastThrottleNoticeTime.compareAndSet(last, now)) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_THROTTLE_NOTICE, newLimit, MAX_WORKING_COUNT);
        }
    }

    @Override
    public void run() {
        final String poolThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName(String.format("Chunky-%s Thread", selection.world().getName()));
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
            // Wait for a dispatch slot — park 1ms at a time so stop() is responsive.
            // Math.max(1, ...) guards against dispatchLimit ever hitting 0.
            while (!stopped) {
                if (inFlight.get() < Math.max(1, dispatchLimit.get())) break;
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
                            adjustThrottle(elapsed);
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
