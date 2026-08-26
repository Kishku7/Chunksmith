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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GsonConfig implements Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // slf4j, NOT java.util.logging. The loaders do not route JUL to the game log, so every one
    // of the range warnings below was INVISIBLE to operators -- a config value silently clamped
    // with the explanation going nowhere. Same bug class that hid the drain diagnostics on
    // 2026-08-20 until ChunkResidency was switched over.
    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");
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
    // Auto-pause: how long the server must hold a state before the run is stopped or restarted.
    // 120 s is long enough to ride out an autosave or a structure-heavy patch of terrain, and short
    // enough that an operator watching a stalled run does not sit through many minutes of stutter.
    private static final int AUTO_PAUSE_GRACE_MIN = 10;
    private static final int AUTO_PAUSE_GRACE_MAX = 3600;
    private static final int AUTO_PAUSE_GRACE_DEFAULT = 120;
    // How much tick time a pre-gen may ADD on top of whatever the server already costs. See
    // Config#getThrottleTickBudgetMillis: an absolute target is unusable on a server whose idle
    // baseline already sits at it. 25 ms is a quarter of a 100 ms tick and was measured to be more
    // than the whole run was costing (10 ms) on the server that exposed this.
    private static final long TICK_BUDGET_MIN = 5L;
    private static final long TICK_BUDGET_MAX = 500L;
    private static final long TICK_BUDGET_DEFAULT = 25L;
    // Reserved per online player, taken out of our allowance. 20 ms is roughly double what a player
    // was measured to cost, matching the "twice the measured cost" rule used for our own budget.
    private static final long PLAYER_RESERVE_MIN = 0L;
    private static final long PLAYER_RESERVE_MAX = 200L;
    private static final long PLAYER_RESERVE_DEFAULT = 20L;
    // Absolute playability ceiling. 150 ms is ~6.7 TPS: slow, but a server you can still be on.
    // Vanilla's own target is 50 ms, so this already grants a pre-gen three times the normal budget.
    private static final long CEILING_MIN = 60L;
    private static final long CEILING_MAX = 2000L;
    private static final long CEILING_DEFAULT = 150L;
    // Governor for the LOD sink. Voxy's ingest queue is unbounded and never reports saturation, so
    // this is the only thing standing between a fast pregen and an OOM.
    private static final long MAX_LOD_QUEUE_MIN = 16L;
    private static final long MAX_LOD_QUEUE_MAX = 100_000L;
    private static final long MAX_LOD_QUEUE_DEFAULT = 512L;
    private static final long DISPATCH_MAX_CONCURRENT_MIN = 1L;
    private static final long DISPATCH_MAX_CONCURRENT_MAX = 4096L;
    /**
     * Default pipeline width, scaled to the box.
     *
     * <p>The old fixed 50 was measured leaving throughput on the table: on an 8-core dedicated server
     * (2026-08-20) 50 gave 31.6 cps while 200 gave 43.9 -- a 39 percent gain -- with residency and heap
     * no worse, and no keep-up warnings either way. 600 gave 42.4, i.e. nothing more, because the real
     * ceiling past that point is vanilla promoting roughly 2.2 chunks per tick at 20 tps.
     *
     * <p>So the knee is around 25 per core rather than a fixed number, and a fixed 200 would be wrong
     * on a 2-core VPS for the same reason 50 was wrong on an 8-core box. Floor stays at the historic 50
     * so no machine gets slower than it was; ceiling at 400 because nothing above the knee helps and
     * every extra slot costs resident chunks.
     *
     * <p>The original {@code chunksmith.maxWorkingCount} system property still wins when set, so an
     * operator who already tuned this on the command line is never silently overridden.
     */
    private static final long DISPATCH_MAX_CONCURRENT_DEFAULT =
            com.kishku7.chunksmith.util.Input.tryInteger(System.getProperty("chunksmith.maxWorkingCount"))
                    .map(Long::valueOf)
                    .orElseGet(() -> Math.min(400L,
                            Math.max(50L, Runtime.getRuntime().availableProcessors() * 25L)));
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
    // The LOD backchannel port. 0 = derive it from the game port (gamePort + 1), which is what
    // Chunksmith did unconditionally before 3.14.0 and remains the default. An explicit value is
    // floored at 1024 because binding a privileged port is not something a game server should be
    // asking for, and a value below it is far more likely to be a typo than an intention.
    private static final int BACKCHANNEL_PORT_DERIVE = 0;
    private static final int BACKCHANNEL_PORT_MIN = 1024;
    private static final int BACKCHANNEL_PORT_MAX = 65535;
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
            LOGGER.warn(String.format("Chunksmith: throttleTargetMspt %.1f is out of range [%.1f, %.1f], using %.1f",
                    raw, TARGET_MSPT_MIN, TARGET_MSPT_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottleMaxChunkMillis() {
        final long raw = Optional.ofNullable(configModel.throttleMaxChunkMillis).orElse(MAX_CHUNK_MILLIS_DEFAULT);
        final long clamped = Math.max(MAX_CHUNK_MILLIS_MIN, Math.min(MAX_CHUNK_MILLIS_MAX, raw));
        if (raw != clamped) {
            LOGGER.warn(String.format("Chunksmith: throttleMaxChunkMillis %d is out of range [%d, %d], using %d",
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
            LOGGER.warn(String.format("Chunksmith: throttleMaxQueuedWrites %d is out of range [%d, %d], using %d",
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
            LOGGER.warn(String.format("Chunksmith: throttleMaxAddedChunks %d is out of range [%d, %d], using %d",
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
            LOGGER.warn(String.format("Chunksmith: throttleMaxHeapPercent %d is out of range [%d, %d], using %d",
                    raw, MAX_HEAP_PERCENT_MIN, MAX_HEAP_PERCENT_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public boolean isAutoPauseEnabled() {
        return Optional.ofNullable(configModel.autoPauseOnOverload).orElse(true);
    }

    @Override
    public int getAutoPauseGraceSeconds() {
        final int raw = Optional.ofNullable(configModel.autoPauseGraceSeconds).orElse(AUTO_PAUSE_GRACE_DEFAULT);
        final int clamped = Math.max(AUTO_PAUSE_GRACE_MIN, Math.min(AUTO_PAUSE_GRACE_MAX, raw));
        if (raw != clamped) {
            LOGGER.warn(String.format("Chunksmith: autoPauseGraceSeconds %d is out of range [%d, %d], using %d",
                    raw, AUTO_PAUSE_GRACE_MIN, AUTO_PAUSE_GRACE_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottleTickBudgetMillis() {
        final long raw = Optional.ofNullable(configModel.throttleTickBudgetMillis).orElse(TICK_BUDGET_DEFAULT);
        if (raw <= 0L) {
            return 0L;
        }
        final long clamped = Math.max(TICK_BUDGET_MIN, Math.min(TICK_BUDGET_MAX, raw));
        if (raw != clamped) {
            LOGGER.warn(String.format("Chunksmith: throttleTickBudgetMillis %d is out of range [%d, %d], using %d",
                    raw, TICK_BUDGET_MIN, TICK_BUDGET_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottlePlayerReserveMillis() {
        final long raw = Optional.ofNullable(configModel.throttlePlayerReserveMillis).orElse(PLAYER_RESERVE_DEFAULT);
        final long clamped = Math.max(PLAYER_RESERVE_MIN, Math.min(PLAYER_RESERVE_MAX, raw));
        if (raw != clamped) {
            LOGGER.warn(String.format("Chunksmith: throttlePlayerReserveMillis %d is out of range [%d, %d], using %d",
                    raw, PLAYER_RESERVE_MIN, PLAYER_RESERVE_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getThrottleCeilingMillis() {
        final long raw = Optional.ofNullable(configModel.throttleCeilingMillis).orElse(CEILING_DEFAULT);
        if (raw <= 0L) {
            return 0L;
        }
        final long clamped = Math.max(CEILING_MIN, Math.min(CEILING_MAX, raw));
        if (raw != clamped) {
            LOGGER.warn(String.format("Chunksmith: throttleCeilingMillis %d is out of range [%d, %d], using %d",
                    raw, CEILING_MIN, CEILING_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public LodMode getLodMode() {
        final String raw = configModel.lodEnabled;
        final LodMode mode = LodMode.parse(raw);
        if (mode == null) {
            LOGGER.warn("Chunksmith: lodEnabled '" + raw
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
            LOGGER.warn(String.format("Chunksmith: throttleMaxLodQueue %d is out of range [%d, %d], using %d",
                    raw, MAX_LOD_QUEUE_MIN, MAX_LOD_QUEUE_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public long getDispatchMaxConcurrent() {
        final long raw = Optional.ofNullable(configModel.dispatchMaxConcurrent)
                .orElse(DISPATCH_MAX_CONCURRENT_DEFAULT);
        final long clamped = Math.max(DISPATCH_MAX_CONCURRENT_MIN,
                Math.min(DISPATCH_MAX_CONCURRENT_MAX, raw));
        if (raw != clamped) {
            LOGGER.warn(String.format(
                    "Chunksmith: dispatchMaxConcurrent %d is out of range [%d, %d], using %d",
                    raw, DISPATCH_MAX_CONCURRENT_MIN, DISPATCH_MAX_CONCURRENT_MAX, clamped));
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
            LOGGER.warn(String.format("Chunksmith: pregenSettleDelayTicks %d is out of range [0, %d],"
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
            LOGGER.warn(String.format("Chunksmith: pregenSettleMaxHeld %d is out of range [%d, %d], using %d",
                    raw, SETTLE_MAX_HELD_MIN, SETTLE_MAX_HELD_MAX, clamped));
        }
        return clamped;
    }

    @Override
    public int getPregenSettleRadius() {
        final int raw = Optional.ofNullable(configModel.pregenSettleRadius).orElse(SETTLE_RADIUS_DEFAULT);
        final int clamped = Math.max(1, Math.min(SETTLE_RADIUS_MAX, raw));
        if (raw != clamped) {
            LOGGER.warn(String.format("Chunksmith: pregenSettleRadius %d is out of range [1, %d],"
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

    @Override
    public int getLodBackchannelPort() {
        final int raw = Optional.ofNullable(configModel.lodBackchannelPort).orElse(BACKCHANNEL_PORT_DERIVE);
        if (raw == BACKCHANNEL_PORT_DERIVE) {
            return BACKCHANNEL_PORT_DERIVE;
        }
        if (raw < BACKCHANNEL_PORT_MIN || raw > BACKCHANNEL_PORT_MAX) {
            // Say it out loud. A silently corrected port is the worst outcome here: the operator
            // opens the number they wrote in the file and the server is listening somewhere else.
            LOGGER.warn("Chunksmith: lodBackchannelPort " + raw + " is outside "
                    + BACKCHANNEL_PORT_MIN + "-" + BACKCHANNEL_PORT_MAX
                    + "; deriving the port from the game port instead");
            return BACKCHANNEL_PORT_DERIVE;
        }
        return raw;
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
    public void setThrottleTickBudgetMillis(final long millis) {
        configModel.throttleTickBudgetMillis = millis <= 0L
                ? 0L
                : Math.max(TICK_BUDGET_MIN, Math.min(TICK_BUDGET_MAX, millis));
        saveConfig();
    }

    @Override
    public void setThrottlePlayerReserveMillis(final long millis) {
        configModel.throttlePlayerReserveMillis =
                Math.max(PLAYER_RESERVE_MIN, Math.min(PLAYER_RESERVE_MAX, millis));
        saveConfig();
    }

    @Override
    public void setThrottleCeilingMillis(final long millis) {
        configModel.throttleCeilingMillis = millis <= 0L
                ? 0L
                : Math.max(CEILING_MIN, Math.min(CEILING_MAX, millis));
        saveConfig();
    }

    @Override
    public void setAutoPauseEnabled(final boolean enabled) {
        configModel.autoPauseOnOverload = enabled;
        saveConfig();
    }

    @Override
    public void setAutoPauseGraceSeconds(final int seconds) {
        configModel.autoPauseGraceSeconds =
                Math.max(AUTO_PAUSE_GRACE_MIN, Math.min(AUTO_PAUSE_GRACE_MAX, seconds));
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
    public void setDispatchMaxConcurrent(final long chunks) {
        configModel.dispatchMaxConcurrent = Math.max(DISPATCH_MAX_CONCURRENT_MIN,
                Math.min(DISPATCH_MAX_CONCURRENT_MAX, chunks));
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
    public void setLodBackchannelPort(final int port) {
        // Out-of-range collapses to DERIVE rather than clamping to the nearest legal port. Clamping
        // would answer "set it to 80" with "it is now 1024", which is a different server than the
        // one that was asked for; deriving is at least the documented default and is what the
        // getter already reports for the same input.
        configModel.lodBackchannelPort =
                (port < BACKCHANNEL_PORT_MIN || port > BACKCHANNEL_PORT_MAX)
                        ? BACKCHANNEL_PORT_DERIVE
                        : port;
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
        private Long throttleTickBudgetMillis = TICK_BUDGET_DEFAULT;
        private Long throttlePlayerReserveMillis = PLAYER_RESERVE_DEFAULT;
        private Long throttleCeilingMillis = CEILING_DEFAULT;
        private Boolean autoPauseOnOverload = true;
        private Integer autoPauseGraceSeconds = AUTO_PAUSE_GRACE_DEFAULT;
        // TRISTATE, written as the string "auto" by default. Declared String, not Boolean, ON PURPOSE:
        // Gson's String adapter coerces a JSON boolean to "true"/"false", so a config that already says
        // `"lodEnabled": false` (or true) from an older Chunksmith still parses, still means exactly what
        // it said, and is never rewritten behind the operator's back.
        private String lodEnabled = "auto";
        private Long throttleMaxLodQueue = MAX_LOD_QUEUE_DEFAULT;
        private Long dispatchMaxConcurrent = DISPATCH_MAX_CONCURRENT_DEFAULT;
        private Boolean lodDhOverride = false;
        // 0 = derive from the game port. See Config#getLodBackchannelPort.
        private Integer lodBackchannelPort = BACKCHANNEL_PORT_DERIVE;
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
        public Long getThrottleCeilingMillis() { return throttleCeilingMillis; }
        public void setThrottleCeilingMillis(final Long throttleCeilingMillis) { this.throttleCeilingMillis = throttleCeilingMillis; }
        public Long getThrottlePlayerReserveMillis() { return throttlePlayerReserveMillis; }
        public void setThrottlePlayerReserveMillis(final Long throttlePlayerReserveMillis) { this.throttlePlayerReserveMillis = throttlePlayerReserveMillis; }
        public Long getThrottleTickBudgetMillis() { return throttleTickBudgetMillis; }
        public void setThrottleTickBudgetMillis(final Long throttleTickBudgetMillis) { this.throttleTickBudgetMillis = throttleTickBudgetMillis; }
        public Long getThrottleMaxHeapPercent() { return throttleMaxHeapPercent; }
        public void setThrottleMaxHeapPercent(final Long throttleMaxHeapPercent) { this.throttleMaxHeapPercent = throttleMaxHeapPercent; }
        public Boolean getAutoPauseOnOverload() { return autoPauseOnOverload; }
        public void setAutoPauseOnOverload(final Boolean autoPauseOnOverload) { this.autoPauseOnOverload = autoPauseOnOverload; }
        public Integer getAutoPauseGraceSeconds() { return autoPauseGraceSeconds; }
        public void setAutoPauseGraceSeconds(final Integer autoPauseGraceSeconds) { this.autoPauseGraceSeconds = autoPauseGraceSeconds; }
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
