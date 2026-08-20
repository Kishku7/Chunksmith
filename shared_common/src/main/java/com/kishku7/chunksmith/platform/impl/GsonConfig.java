package com.kishku7.chunksmith.platform.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.LodMode;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.Translator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class GsonConfig implements Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = Logger.getLogger("Chunksmith");
    // Target ms/tick the throttle steers toward. A healthy 20 TPS server measures ~50 ms,
    // so the floor of this range is 50; the default leaves a small margin above it.
    private static final double TARGET_MSPT_MIN = 54.0;
    private static final double TARGET_MSPT_MAX = 1000.0;
    private static final double TARGET_MSPT_DEFAULT = 150.0;
    // Absolute per-chunk latency backstop (ms).
    private static final long MAX_CHUNK_MILLIS_MIN = 100L;
    private static final long MAX_CHUNK_MILLIS_MAX = 60_000L;
    private static final long MAX_CHUNK_MILLIS_DEFAULT = 750L;
    // Maximum queued (unflushed) chunk writes before generation dispatch is held off.
    // 0 disables. Hysteresis resumes dispatch once the backlog drains to half this value.
    private static final long MAX_QUEUED_WRITES_MIN = 50L;
    private static final long MAX_QUEUED_WRITES_MAX = 1_000_000L;
    private static final long MAX_QUEUED_WRITES_DEFAULT = 800L;
    // Maximum chunks a run may ADD to the resident set before dispatch is held off. 0 disables.
    // Measured as a DELTA against residency at task start (3.5.1); the 3.5.0 absolute form tripped on
    // whatever the server already had. A legitimate sweep frontier is roughly 16x the selection radius
    // in chunks, so 20000 clears a ~1200-chunk-radius run untouched, while the runaway this bounds
    // added ~55000 over baseline on a 470-chunk-radius selection. Hysteresis resumes dispatch at half.
    private static final long MAX_ADDED_CHUNKS_MIN = 1_000L;
    private static final long MAX_ADDED_CHUNKS_MAX = 5_000_000L;
    // DEFAULT 0 = OFF as of 3.5.5. A chunk count cannot be tuned to mean the same thing on two
    // worlds -- a chunk is worth wildly different amounts of heap depending on the entities and block
    // entities that came with it -- and on a live server this gate closed at 22,000 chunks while the
    // heap sat at 40 percent, stuttering a perfectly healthy run to 60 chunks per two minutes. Memory
    // is governed by throttleMaxHeapPercent and load by tick health; this stays as an expert knob.
    private static final long MAX_ADDED_CHUNKS_DEFAULT = 0L;
    // Heap ceiling, as a percentage of -Xmx, above which dispatch is held. 0 disables. 85 leaves the
    // collector room to work while still stopping well short of the thrash that precedes an OOM.
    private static final long MAX_HEAP_PERCENT_MIN = 50L;
    private static final long MAX_HEAP_PERCENT_MAX = 99L;
    private static final long MAX_HEAP_PERCENT_DEFAULT = 85L;
    // Governor for the LOD sink. Voxy's ingest queue is unbounded and never reports saturation, so
    // this is the only thing standing between a fast pregen and an OOM.
    private static final long MAX_LOD_QUEUE_MIN = 16L;
    private static final long MAX_LOD_QUEUE_MAX = 100_000L;
    private static final long MAX_LOD_QUEUE_DEFAULT = 512L;
    private static final long SETTLE_DELAY_DEFAULT = 40L;
    private static final long SETTLE_DELAY_MAX = 600L;
    private static final int SETTLE_RADIUS_DEFAULT = 7;
    private static final int SETTLE_RADIUS_MAX = 16;
    // Hard ceiling on the settle frontier, counted in TICKETS -- but paid for in TICKETS x HALO.
    //
    // A ticket at FULL level does not hold one chunk. Vanilla's distance manager propagates the level
    // outward one ring at a time until it passes the maximum, so a single held ticket drags roughly
    // eleven rings of neighbours into the "loaded" band with it. Measured on a live 1.21.11 pre-gen:
    // 20 held tickets -> 3,507 resident chunks; ~400 held -> 10,167 resident. Call it 25 resident
    // chunks per held ticket at pre-gen clustering.
    //
    // The 3.5.0 default of 8192 therefore authorised something like 200,000 resident chunks, and
    // 3.4.1 -- which had no cap at all -- is how a live server reached 75,045 and died to the
    // watchdog. 256 is about 6,000 resident chunks on that measurement, which an 8 GB heap carries
    // comfortably. Raise it only with that arithmetic in mind.
    private static final long SETTLE_MAX_HELD_MIN = 16L;
    private static final long SETTLE_MAX_HELD_MAX = 1_000_000L;
    private static final long SETTLE_MAX_HELD_DEFAULT = 256L;
    private final Path savePath;
    private ConfigModel configModel = new ConfigModel();

    public GsonConfig(final Path savePath) {
        this.savePath = savePath;
        if (Files.exists(this.savePath)) {
            reload();
        } else {
            saveConfig();
        }
        Translator.setLanguage(getLanguage());
    }

    @Override
    public Path getDirectory() {
        return savePath.getParent();
    }

    @Override
    public int getVersion() {
        return Optional.ofNullable(configModel.version).orElse(0);
    }

    @Override
    public String getLanguage() {
        return Optional.ofNullable(configModel.language).map(Input::checkLanguage).orElse("en");
    }

    @Override
    public boolean getContinueOnRestart() {
        return Optional.ofNullable(configModel.continueOnRestart).orElse(false);
    }

    @Override
    public boolean isForceLoadExistingChunks() {
        return Optional.ofNullable(configModel.forceLoadExistingChunks).orElse(false);
    }

    @Override
    public boolean isSilent() {
        return Optional.ofNullable(configModel.silent).orElse(false);
    }

    @Override
    public void setSilent(final boolean silent) {
        configModel.silent = silent;
        saveConfig();
    }

    @Override
    public int getUpdateInterval() {
        return Optional.ofNullable(configModel.updateInterval).orElse(1);
    }

    @Override
    public void setUpdateInterval(final int updateInterval) {
        configModel.updateInterval = Math.max(0, updateInterval);
        saveConfig();
    }

    @Override
    public boolean isIoThrottleEnabled() {
        return Optional.ofNullable(configModel.ioThrottle).orElse(true);
    }

    @Override
    public double getThrottleTargetMspt() {
        final double raw = Optional.ofNullable(configModel.throttleTargetMspt).orElse(TARGET_MSPT_DEFAULT);
        final double clamped = Math.max(TARGET_MSPT_MIN, Math.min(TARGET_MSPT_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: throttleTargetMspt %.1f is out of range [%.1f, %.1f], using %.1f",
                    raw, TARGET_MSPT_MIN, TARGET_MSPT_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottleMaxChunkMillis() {
        final long raw = Optional.ofNullable(configModel.throttleMaxChunkMillis).orElse(MAX_CHUNK_MILLIS_DEFAULT);
        final long clamped = Math.max(MAX_CHUNK_MILLIS_MIN, Math.min(MAX_CHUNK_MILLIS_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: throttleMaxChunkMillis %d is out of range [%d, %d], using %d",
                    raw, MAX_CHUNK_MILLIS_MIN, MAX_CHUNK_MILLIS_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottleMaxQueuedWrites() {
        final long raw = Optional.ofNullable(configModel.throttleMaxQueuedWrites).orElse(MAX_QUEUED_WRITES_DEFAULT);
        if (raw <= 0L) {
            return 0L;
        }
        final long clamped = Math.max(MAX_QUEUED_WRITES_MIN, Math.min(MAX_QUEUED_WRITES_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: throttleMaxQueuedWrites %d is out of range [%d, %d], using %d",
                    raw, MAX_QUEUED_WRITES_MIN, MAX_QUEUED_WRITES_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottleMaxAddedChunks() {
        final long raw = Optional.ofNullable(configModel.throttleMaxAddedChunks).orElse(MAX_ADDED_CHUNKS_DEFAULT);
        if (raw <= 0L) {
            return 0L;
        }
        final long clamped = Math.max(MAX_ADDED_CHUNKS_MIN, Math.min(MAX_ADDED_CHUNKS_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: throttleMaxAddedChunks %d is out of range [%d, %d], using %d",
                    raw, MAX_ADDED_CHUNKS_MIN, MAX_ADDED_CHUNKS_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottleMaxHeapPercent() {
        final long raw = Optional.ofNullable(configModel.throttleMaxHeapPercent).orElse(MAX_HEAP_PERCENT_DEFAULT);
        if (raw <= 0L) {
            return 0L;
        }
        final long clamped = Math.max(MAX_HEAP_PERCENT_MIN, Math.min(MAX_HEAP_PERCENT_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: throttleMaxHeapPercent %d is out of range [%d, %d], using %d",
                    raw, MAX_HEAP_PERCENT_MIN, MAX_HEAP_PERCENT_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public LodMode getLodMode() {
        final String raw = configModel.lodEnabled;
        final LodMode mode = LodMode.parse(raw);
        if (mode == null) {
            LOGGER.warning("Chunksmith: lodEnabled '" + raw
                    + "' is not one of auto/true/false, using auto");
            return LodMode.AUTO;
        }
        return mode;
    }

    @Override
    public long getThrottleMaxLodQueue() {
        final long raw = Optional.ofNullable(configModel.throttleMaxLodQueue).orElse(MAX_LOD_QUEUE_DEFAULT);
        if (raw <= 0L) {
            return 0L;
        }
        final long clamped = Math.max(MAX_LOD_QUEUE_MIN, Math.min(MAX_LOD_QUEUE_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: throttleMaxLodQueue %d is out of range [%d, %d], using %d",
                    raw, MAX_LOD_QUEUE_MIN, MAX_LOD_QUEUE_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public boolean isPregenSettleEnabled() {
        return Optional.ofNullable(configModel.pregenSettle).orElse(true);
    }

    @Override
    public long getPregenSettleDelayTicks() {
        final long raw = Optional.ofNullable(configModel.pregenSettleDelayTicks)
                .orElse(SETTLE_DELAY_DEFAULT);
        final long clamped = Math.max(0L, Math.min(SETTLE_DELAY_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: pregenSettleDelayTicks %d is out of range [0, %d],"
                    + " using %d", raw, SETTLE_DELAY_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getPregenSettleMaxHeld() {
        final long raw = Optional.ofNullable(configModel.pregenSettleMaxHeld).orElse(SETTLE_MAX_HELD_DEFAULT);
        if (raw <= 0L) {
            return 0L;
        }
        final long clamped = Math.max(SETTLE_MAX_HELD_MIN, Math.min(SETTLE_MAX_HELD_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: pregenSettleMaxHeld %d is out of range [%d, %d], using %d",
                    raw, SETTLE_MAX_HELD_MIN, SETTLE_MAX_HELD_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public int getPregenSettleRadius() {
        final int raw = Optional.ofNullable(configModel.pregenSettleRadius).orElse(SETTLE_RADIUS_DEFAULT);
        final int clamped = Math.max(1, Math.min(SETTLE_RADIUS_MAX, raw));
        if (raw != clamped) {
            LOGGER.warning(String.format("Chunksmith: pregenSettleRadius %d is out of range [1, %d],"
                    + " using %d", raw, SETTLE_RADIUS_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public void setPregenSettleEnabled(final boolean enabled) {
        configModel.pregenSettle = enabled;
        saveConfig();
    }

    @Override
    public void setPregenSettleDelayTicks(final long ticks) {
        // Clamp on the WAY IN as well as the way out. The getter clamps because a hand-edited file can
        // hold anything; doing it here too means the file never contains a value we would refuse to read
        // back, so what /cs settle reports and what the file says can never disagree.
        configModel.pregenSettleDelayTicks = Math.max(0L, Math.min(SETTLE_DELAY_MAX, ticks));
        saveConfig();
    }

    @Override
    public void setPregenSettleRadius(final int radius) {
        configModel.pregenSettleRadius = Math.max(1, Math.min(SETTLE_RADIUS_MAX, radius));
        saveConfig();
    }

    @Override
    public boolean isLodDhOverrideEnabled() {
        return Optional.ofNullable(configModel.lodDhOverride).orElse(false);
    }

    // Every setter below clamps to the SAME range its getter enforces, then saves. Clamping only on
    // read would let the file hold a number the mod refuses to honour, so the file and `/cs set` would
    // disagree about what is in force -- and the file is what an operator inspects when something is wrong.

    @Override
    public void setLanguage(final String language) {
        configModel.language = Input.checkLanguage(language);
        saveConfig();
        Translator.setLanguage(getLanguage());
    }

    @Override
    public void setContinueOnRestart(final boolean continueOnRestart) {
        configModel.continueOnRestart = continueOnRestart;
        saveConfig();
    }

    @Override
    public void setForceLoadExistingChunks(final boolean forceLoadExistingChunks) {
        configModel.forceLoadExistingChunks = forceLoadExistingChunks;
        saveConfig();
    }

    @Override
    public void setIoThrottleEnabled(final boolean enabled) {
        configModel.ioThrottle = enabled;
        saveConfig();
    }

    @Override
    public void setThrottleTargetMspt(final double mspt) {
        configModel.throttleTargetMspt = Math.max(TARGET_MSPT_MIN, Math.min(TARGET_MSPT_MAX, mspt));
        saveConfig();
    }

    @Override
    public void setThrottleMaxChunkMillis(final long millis) {
        configModel.throttleMaxChunkMillis =
                Math.max(MAX_CHUNK_MILLIS_MIN, Math.min(MAX_CHUNK_MILLIS_MAX, millis));
        saveConfig();
    }

    @Override
    public void setThrottleMaxQueuedWrites(final long writes) {
        // 0 is not out of range -- it is the documented "disable the backlog bound" value, so it must
        // survive the clamp rather than being pulled up to the minimum.
        configModel.throttleMaxQueuedWrites = writes <= 0L
                ? 0L
                : Math.max(MAX_QUEUED_WRITES_MIN, Math.min(MAX_QUEUED_WRITES_MAX, writes));
        saveConfig();
    }

    @Override
    public void setThrottleMaxAddedChunks(final long chunks) {
        // 0 is the documented "disable the residency bound" value, as with the write backlog above.
        configModel.throttleMaxAddedChunks = chunks <= 0L
                ? 0L
                : Math.max(MAX_ADDED_CHUNKS_MIN, Math.min(MAX_ADDED_CHUNKS_MAX, chunks));
        saveConfig();
    }

    @Override
    public void setThrottleMaxHeapPercent(final long percent) {
        // 0 disables, as with the other bounds.
        configModel.throttleMaxHeapPercent = percent <= 0L
                ? 0L
                : Math.max(MAX_HEAP_PERCENT_MIN, Math.min(MAX_HEAP_PERCENT_MAX, percent));
        saveConfig();
    }

    @Override
    public void setPregenSettleMaxHeld(final long maxHeld) {
        // 0 disables the cap entirely, so it must survive the clamp.
        configModel.pregenSettleMaxHeld = maxHeld <= 0L
                ? 0L
                : Math.max(SETTLE_MAX_HELD_MIN, Math.min(SETTLE_MAX_HELD_MAX, maxHeld));
        saveConfig();
    }

    @Override
    public void setThrottleMaxLodQueue(final long items) {
        // 0 disables, as above.
        configModel.throttleMaxLodQueue = items <= 0L
                ? 0L
                : Math.max(MAX_LOD_QUEUE_MIN, Math.min(MAX_LOD_QUEUE_MAX, items));
        saveConfig();
    }

    @Override
    public void setLodMode(final LodMode mode) {
        configModel.lodEnabled = mode.name().toLowerCase(java.util.Locale.ROOT);
        saveConfig();
    }

    @Override
    public void setLodDhOverrideEnabled(final boolean enabled) {
        configModel.lodDhOverride = enabled;
        saveConfig();
    }

    @Override
    public void reload() {
        try (final Reader reader = Files.newBufferedReader(savePath)) {
            configModel = GSON.fromJson(reader, ConfigModel.class);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(savePath.getParent());
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (final Writer writer = Files.newBufferedWriter(savePath)) {
            GSON.toJson(configModel, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    private static class ConfigModel {
        private Integer version = 2;
        private String language = "en";
        private Boolean continueOnRestart = false;
        private Boolean forceLoadExistingChunks = false;
        private Boolean silent = false;
        private Integer updateInterval = 1;
        private Boolean ioThrottle = true;
        private Double throttleTargetMspt = TARGET_MSPT_DEFAULT;
        private Long throttleMaxChunkMillis = MAX_CHUNK_MILLIS_DEFAULT;
        private Long throttleMaxQueuedWrites = MAX_QUEUED_WRITES_DEFAULT;
        private Long throttleMaxAddedChunks = MAX_ADDED_CHUNKS_DEFAULT;
        private Long throttleMaxHeapPercent = MAX_HEAP_PERCENT_DEFAULT;
        // TRISTATE, written as the string "auto" by default. Declared String, not Boolean, ON PURPOSE:
        // Gson's String adapter coerces a JSON boolean to "true"/"false", so a config that already says
        // `"lodEnabled": false` (or true) from an older Chunksmith still parses, still means exactly what
        // it said, and is never rewritten behind the operator's back.
        private String lodEnabled = "auto";
        private Long throttleMaxLodQueue = MAX_LOD_QUEUE_DEFAULT;
        private Boolean lodDhOverride = false;
        // ON by default: dropping a chunk the instant it is generated silently breaks every mod that
        // builds on newly generated land (mod_support #14). Off is for a pure terrain pregen.
        private Boolean pregenSettle = true;
        private Long pregenSettleDelayTicks = SETTLE_DELAY_DEFAULT;
        private Integer pregenSettleRadius = SETTLE_RADIUS_DEFAULT;
        private Long pregenSettleMaxHeld = SETTLE_MAX_HELD_DEFAULT;
        private Map<String, TaskModel> tasks;

        public Integer getVersion() { return version; }
        public void setVersion(final Integer version) { this.version = version; }
        public String getLanguage() { return language; }
        public void setLanguage(final String language) { this.language = language; }
        public Boolean getContinueOnRestart() { return continueOnRestart; }
        public void setContinueOnRestart(final Boolean continueOnRestart) { this.continueOnRestart = continueOnRestart; }
        public Boolean getForceLoadExistingChunks() { return forceLoadExistingChunks; }
        public void setForceLoadExistingChunks(final Boolean forceLoadExistingChunks) { this.forceLoadExistingChunks = forceLoadExistingChunks; }
        public Map<String, TaskModel> getTasks() { return tasks; }
        public void setTasks(final Map<String, TaskModel> tasks) { this.tasks = tasks; }
        public boolean isSilent() { return silent; }
        public void setSilent(final boolean silent) { this.silent = silent; }
        public int getUpdateInterval() { return updateInterval; }
        public void setUpdateInterval(final int updateInterval) { this.updateInterval = updateInterval; }
        public Boolean getIoThrottle() { return ioThrottle; }
        public void setIoThrottle(final Boolean ioThrottle) { this.ioThrottle = ioThrottle; }
        public Double getThrottleTargetMspt() { return throttleTargetMspt; }
        public void setThrottleTargetMspt(final Double throttleTargetMspt) { this.throttleTargetMspt = throttleTargetMspt; }
        public Long getThrottleMaxChunkMillis() { return throttleMaxChunkMillis; }
        public void setThrottleMaxChunkMillis(final Long throttleMaxChunkMillis) { this.throttleMaxChunkMillis = throttleMaxChunkMillis; }
        public Long getThrottleMaxQueuedWrites() { return throttleMaxQueuedWrites; }
        public void setThrottleMaxQueuedWrites(final Long throttleMaxQueuedWrites) { this.throttleMaxQueuedWrites = throttleMaxQueuedWrites; }
        public Long getThrottleMaxAddedChunks() { return throttleMaxAddedChunks; }
        public void setThrottleMaxAddedChunks(final Long throttleMaxAddedChunks) { this.throttleMaxAddedChunks = throttleMaxAddedChunks; }
        public Long getThrottleMaxHeapPercent() { return throttleMaxHeapPercent; }
        public void setThrottleMaxHeapPercent(final Long throttleMaxHeapPercent) { this.throttleMaxHeapPercent = throttleMaxHeapPercent; }
        public Long getPregenSettleMaxHeld() { return pregenSettleMaxHeld; }
        public void setPregenSettleMaxHeld(final Long pregenSettleMaxHeld) { this.pregenSettleMaxHeld = pregenSettleMaxHeld; }
    }

    @SuppressWarnings("unused")
    private static class TaskModel {
        private Boolean cancelled;
        private Double radius;
        private Double radiusZ;
        private Double centerX;
        private Double centerZ;
        private String iterator;
        private String shape;
        private Long count;
        private Long time;

        public Boolean getCancelled() { return cancelled; }
        public void setCancelled(final Boolean cancelled) { this.cancelled = cancelled; }
        public Double getRadius() { return radius; }
        public void setRadius(final Double radius) { this.radius = radius; }
        public Double getRadiusZ() { return radiusZ; }
        public void setRadiusZ(final Double radiusZ) { this.radiusZ = radiusZ; }
        public Double getCenterX() { return centerX; }
        public void setCenterX(final Double centerX) { this.centerX = centerX; }
        public Double getCenterZ() { return centerZ; }
        public void setCenterZ(final Double centerZ) { this.centerZ = centerZ; }
        public String getIterator() { return iterator; }
        public void setIterator(final String iterator) { this.iterator = iterator; }
        public String getShape() { return shape; }
        public void setShape(final String shape) { this.shape = shape; }
        public Long getCount() { return count; }
        public void setCount(final Long count) { this.count = count; }
        public Long getTime() { return time; }
        public void setTime(final Long time) { this.time = time; }
    }
}
