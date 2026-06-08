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
    private static final Logger LOGGER = Logger.getLogger("Chunky");
    private static final double SLOW_MULTIPLIER_MIN = 1.5;
    private static final double SLOW_MULTIPLIER_MAX = 10.0;
    private static final double SLOW_MULTIPLIER_DEFAULT = 5.0;
    private static final int FAST_SAMPLE_SIZE_MIN = 5;
    private static final int FAST_SAMPLE_SIZE_MAX = 100;
    private static final int FAST_SAMPLE_SIZE_DEFAULT = 20;
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
    public double getSlowMultiplier() {
        final double raw = Optional.ofNullable(configModel.slowMultiplier).orElse(SLOW_MULTIPLIER_DEFAULT);
        if (raw < SLOW_MULTIPLIER_MIN || raw > SLOW_MULTIPLIER_MAX) {
            LOGGER.warning(String.format("Chunky: slowMultiplier %.1f is out of range [%.1f, %.1f], using %.1f",
                    raw, SLOW_MULTIPLIER_MIN, SLOW_MULTIPLIER_MAX, Math.max(SLOW_MULTIPLIER_MIN, Math.min(SLOW_MULTIPLIER_MAX, raw))));
            return Math.max(SLOW_MULTIPLIER_MIN, Math.min(SLOW_MULTIPLIER_MAX, raw));
        }
        return raw;
    }

    @Override
    public int getFastSampleSize() {
        return Optional.ofNullable(configModel.fastSampleSize).orElse(FAST_SAMPLE_SIZE_DEFAULT);
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
        private Double slowMultiplier = SLOW_MULTIPLIER_DEFAULT;
        private Integer fastSampleSize = FAST_SAMPLE_SIZE_DEFAULT;
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
        public Double getSlowMultiplier() { return slowMultiplier; }
        public void setSlowMultiplier(final Double slowMultiplier) { this.slowMultiplier = slowMultiplier; }
        public Integer getFastSampleSize() { return fastSampleSize; }
        public void setFastSampleSize(final Integer fastSampleSize) { this.fastSampleSize = fastSampleSize; }
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
