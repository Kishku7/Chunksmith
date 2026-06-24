package org.popcraft.chunky.util;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic collector for vanilla "Detected setBlock in a far chunk" worldgen overreaches.
 * <p>
 * A worldgen feature/structure that tries to {@code setBlock} outside the current generation
 * step's write radius is refused by vanilla and (normally) logged once per block, producing
 * bursts of 100-200 lines. Chunksmith suppresses those at the source and routes the structured
 * event here instead.
 * <p>
 * Reports are collapsed to single, comprehensive lines:
 * <ul>
 *   <li>A burst (one structure spilling out of one chunk's one gen step) shares the key
 *       (dimension, source chunk, step, feature) and is aggregated into ONE detailed line,
 *       flushed after a short debounce once the burst goes silent.</li>
 *   <li>Repeats of the same feature across many chunks are throttled: the first is detailed,
 *       subsequent ones are counted into a per-feature rollup emitted periodically, and a
 *       one-line summary per feature is printed when generation finishes.</li>
 * </ul>
 * Two feed paths, both funnelling into the same aggregation:
 * <ul>
 *   <li>{@link #record} - STRUCTURED, from the Fabric/NeoForge mixin: full data (source chunk,
 *       dimension, writeRadius) captured directly off the {@code WorldGenRegion}.</li>
 *   <li>{@link #recordFromMessage} - BEST-EFFORT, from the plugin's Log4j filter on
 *       Spigot/Paper/Folia, where there is no mixin: the vanilla log line is parsed for what it
 *       carries (feature, step, far chunk, Y). Source chunk / dimension / writeRadius are not in
 *       the message, so those are omitted from the best-effort line.</li>
 * </ul>
 * {@link #record}/{@link #recordFromMessage} run on worldgen worker threads; {@link #tick} runs on
 * the server thread (mixin) or a plugin scheduler tick.
 */
public final class WorldgenOverreachReporter {
    private static final WorldgenOverreachReporter INSTANCE = new WorldgenOverreachReporter();

    public static WorldgenOverreachReporter get() {
        return INSTANCE;
    }

    // Vanilla (Util.logAndPauseIfInIde) far-write line, byte-stable across 26.x:
    //   Detected setBlock in a far chunk [<cx>, <cz>], pos: BlockPos{x=.., y=<y>, z=..}, status: <step>[, currently generating: <feature>]
    public static final String FAR_CHUNK_MARKER = "Detected setBlock in a far chunk";
    private static final Pattern FAR_CHUNK = Pattern.compile("far chunk \\[(-?\\d+), *(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("y=(-?\\d+)");
    private static final Pattern STATUS = Pattern.compile("status: *(.*?)(?:, currently generating:|$)");
    private static final Pattern FEATURE = Pattern.compile("currently generating: *(.+?)\\s*$");

    private final Logger log = LoggerFactory.getLogger("Chunksmith");
    private final Map<String, Bucket> active = new ConcurrentHashMap<>();
    private final Map<String, FeatureStats> features = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile long debounceMillis = 500L;     // burst is "done" after this much silence
    private volatile long rollupMillis = 30_000L;    // how often to emit a per-feature rollup
    private volatile boolean wasRunning = false;

    private WorldgenOverreachReporter() {
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public void configure(final long debounceMillis, final long rollupMillis) {
        this.debounceMillis = Math.max(0L, debounceMillis);
        this.rollupMillis = Math.max(1_000L, rollupMillis);
    }

    /** Record one refused far-chunk setBlock with full structured data (mixin path). */
    public void record(final String feature, final String step, final String dimension,
                       final int sourceChunkX, final int sourceChunkZ,
                       final int farChunkX, final int farChunkZ, final int y, final int writeRadius) {
        final String f = (feature == null || feature.isEmpty()) ? "<unknown>" : feature;
        final String s = (step == null) ? "?" : step;
        final String d = (dimension == null) ? "?" : dimension;
        final String key = d + '|' + f + '|' + s + '|' + sourceChunkX + '|' + sourceChunkZ;
        accumulate(key, f, s, d, sourceChunkX, sourceChunkZ, writeRadius, false, farChunkX, farChunkZ, y);
    }

    /**
     * Parse a vanilla far-chunk log line and record it (plugin best-effort path). Only the data the
     * line carries is available: feature, step, far chunk, Y. Returns true if the line was a
     * far-chunk overreach (and was consumed), false otherwise.
     */
    public boolean recordFromMessage(final String message) {
        if (message == null || !message.contains(FAR_CHUNK_MARKER)) {
            return false;
        }
        try {
            final Matcher fc = FAR_CHUNK.matcher(message);
            int farX = 0;
            int farZ = 0;
            if (fc.find()) {
                farX = Integer.parseInt(fc.group(1));
                farZ = Integer.parseInt(fc.group(2));
            }
            int y = Integer.MIN_VALUE;
            final Matcher my = POS_Y.matcher(message);
            if (my.find()) {
                y = Integer.parseInt(my.group(1));
            }
            String step = "?";
            final Matcher ms = STATUS.matcher(message);
            if (ms.find()) {
                step = ms.group(1).trim();
            }
            String feature = "<unknown>";
            final Matcher mf = FEATURE.matcher(message);
            if (mf.find()) {
                feature = mf.group(1).trim();
            }
            // No dimension/source chunk/writeRadius in the line -> collapse by feature+step only.
            final String key = "best|" + feature + '|' + step;
            accumulate(key, feature, step, null, Integer.MIN_VALUE, Integer.MIN_VALUE, -1, true, farX, farZ, y);
        } catch (final RuntimeException ignored) {
            // Malformed/parse failure: still consumed (it was a far-chunk line), just not aggregated.
        }
        return true;
    }

    private void accumulate(final String key, final String feature, final String step, final String dimension,
                            final int sourceChunkX, final int sourceChunkZ, final int writeRadius,
                            final boolean bestEffort, final int farChunkX, final int farChunkZ, final int y) {
        if (!enabled) {
            return;
        }
        final Bucket b = active.computeIfAbsent(key,
                k -> new Bucket(feature, step, dimension, sourceChunkX, sourceChunkZ, writeRadius, bestEffort));
        synchronized (b) {
            b.count++;
            if (farChunkX < b.minFarX) b.minFarX = farChunkX;
            if (farChunkX > b.maxFarX) b.maxFarX = farChunkX;
            if (farChunkZ < b.minFarZ) b.minFarZ = farChunkZ;
            if (farChunkZ > b.maxFarZ) b.maxFarZ = farChunkZ;
            if (y != Integer.MIN_VALUE) {
                if (y < b.minY) b.minY = y;
                if (y > b.maxY) b.maxY = y;
            }
            b.lastUpdate = System.currentTimeMillis();
        }
    }

    /** Flush ready bursts, emit rollups, and summarize at end-of-run. Called once per server tick. */
    public void tick(final boolean taskRunning) {
        if (!enabled) {
            return;
        }
        if (taskRunning && !wasRunning) {
            log.info("[Chunksmith] worldgen overreach diagnostic active - watching this run for worldgen features writing outside their chunk.");
        }
        final long now = System.currentTimeMillis();
        flushIdle(now, false);
        emitRollups(now);
        if (wasRunning && !taskRunning) {
            // generation just finished: force-flush everything and print summaries
            flushIdle(now, true);
            summarizeAndReset();
        }
        wasRunning = taskRunning;
    }

    private void flushIdle(final long now, final boolean force) {
        for (final Iterator<Map.Entry<String, Bucket>> it = active.entrySet().iterator(); it.hasNext(); ) {
            final Bucket b = it.next().getValue();
            final Bucket snap;
            synchronized (b) {
                if (!force && now - b.lastUpdate < debounceMillis) {
                    continue;
                }
                snap = b.copy();
            }
            it.remove();
            final FeatureStats fs = features.computeIfAbsent(b.feature, k -> new FeatureStats());
            fs.totalChunks++;
            fs.totalBlocks += snap.count;
            if (!fs.firstEmitted) {
                fs.firstEmitted = true;
                fs.lastRollupBlocks = fs.totalBlocks;
                fs.lastRollupAt = now;
                log.warn(detailedLine(snap));
            }
        }
    }

    private void emitRollups(final long now) {
        for (final Map.Entry<String, FeatureStats> e : features.entrySet()) {
            final FeatureStats fs = e.getValue();
            if (fs.firstEmitted && now - fs.lastRollupAt >= rollupMillis && fs.totalBlocks > fs.lastRollupBlocks) {
                final long delta = fs.totalBlocks - fs.lastRollupBlocks;
                log.warn(String.format(
                        "[Chunksmith] overreach (cont.): %s - +%d more blocks refused; now %d chunks / %d blocks this run.",
                        e.getKey(), delta, fs.totalChunks, fs.totalBlocks));
                fs.lastRollupBlocks = fs.totalBlocks;
                fs.lastRollupAt = now;
            }
        }
    }

    private void summarizeAndReset() {
        for (final Map.Entry<String, FeatureStats> e : features.entrySet()) {
            final FeatureStats fs = e.getValue();
            log.warn(String.format(
                    "[Chunksmith] overreach summary: %s - clipped in %d chunks (%d blocks refused) this run. "
                            + "Likely a multi-chunk structure placed as a feature; report to the owning mod.",
                    e.getKey(), fs.totalChunks, fs.totalBlocks));
        }
        features.clear();
        active.clear();
    }

    private String detailedLine(final Bucket b) {
        final String yRange = (b.minY == Integer.MAX_VALUE) ? "Y ?" : String.format("Y %d..%d", b.minY, b.maxY);
        if (b.bestEffort) {
            return String.format(
                    "[Chunksmith] worldgen overreach: %s (step %s) - %d blocks refused into far chunks "
                            + "[%d,%d]..[%d,%d] (%s); vanilla refused the writes at the chunk boundary. "
                            + "Likely a multi-chunk structure placed as a feature; report to the owning mod. "
                            + "(best-effort: source chunk/dimension/writeRadius unavailable on this platform)",
                    b.feature, b.step, b.count, b.minFarX, b.minFarZ, b.maxFarX, b.maxFarZ, yRange);
        }
        return String.format(
                "[Chunksmith] worldgen overreach: %s (step %s) @ chunk[%d,%d] %s - %d blocks refused into far chunks "
                        + "[%d,%d]..[%d,%d] (%s); vanilla writeRadius=%d, structure clipped at chunk boundary.",
                b.feature, b.step, b.sourceChunkX, b.sourceChunkZ, b.dimension, b.count,
                b.minFarX, b.minFarZ, b.maxFarX, b.maxFarZ, yRange, b.writeRadius);
    }

    private static final class Bucket {
        final String feature;
        final String step;
        final String dimension;
        final int sourceChunkX;
        final int sourceChunkZ;
        final int writeRadius;
        final boolean bestEffort;
        long count;
        int minFarX = Integer.MAX_VALUE, maxFarX = Integer.MIN_VALUE;
        int minFarZ = Integer.MAX_VALUE, maxFarZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        long lastUpdate;

        Bucket(final String feature, final String step, final String dimension,
               final int sourceChunkX, final int sourceChunkZ, final int writeRadius, final boolean bestEffort) {
            this.feature = feature;
            this.step = step;
            this.dimension = dimension;
            this.sourceChunkX = sourceChunkX;
            this.sourceChunkZ = sourceChunkZ;
            this.writeRadius = writeRadius;
            this.bestEffort = bestEffort;
        }

        Bucket copy() {
            final Bucket c = new Bucket(feature, step, dimension, sourceChunkX, sourceChunkZ, writeRadius, bestEffort);
            c.count = count;
            c.minFarX = minFarX;
            c.maxFarX = maxFarX;
            c.minFarZ = minFarZ;
            c.maxFarZ = maxFarZ;
            c.minY = minY;
            c.maxY = maxY;
            c.lastUpdate = lastUpdate;
            return c;
        }
    }

    private static final class FeatureStats {
        long totalChunks;
        long totalBlocks;
        boolean firstEmitted;
        long lastRollupBlocks;
        long lastRollupAt;
    }
}
