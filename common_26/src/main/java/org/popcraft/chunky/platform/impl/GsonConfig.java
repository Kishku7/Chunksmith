package org.popcraft.chunky.platform.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.popcraft.chunky.platform.Config;
import org.popcraft.chunky.util.Input;
import org.popcraft.chunky.util.Translator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public class GsonConfig implements Config {
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
    }

    @Override
    public int getUpdateInterval() {
        return Optional.ofNullable(configModel.updateInterval).orElse(1);
    }

    @Override
    public void setUpdateInterval(final int updateInterval) {
        configModel.updateInterval = updateInterval;
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
