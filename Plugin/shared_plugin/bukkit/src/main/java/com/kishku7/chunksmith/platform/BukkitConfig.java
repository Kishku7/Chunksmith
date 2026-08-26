package com.kishku7.chunksmith.platform;

import org.bukkit.configuration.file.FileConfigurationOptions;
import com.kishku7.chunksmith.ChunksmithBukkit;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.Translator;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class BukkitConfig implements Config {
    private static final List<String> HEADER = Arrays.asList("Chunksmith Configuration", "https://github.com/pop4959/Chunksmith/wiki/Configuration");
    private final ChunksmithBukkit plugin;

    public BukkitConfig(final ChunksmithBukkit plugin) {
        this.plugin = plugin;
        final FileConfigurationOptions options = plugin.getConfig().options();
        options.copyDefaults(true);
        try {
            FileConfigurationOptions.class.getMethod("header", String.class).invoke(options, String.join("\n", HEADER));
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            options.setHeader(HEADER);
        }
        plugin.saveConfig();
        Translator.setLanguage(getLanguage());
    }

    @Override
    public Path getDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public int getVersion() {
        return plugin.getConfig().getInt("version", 0);
    }

    @Override
    public String getLanguage() {
        return Input.checkLanguage(plugin.getConfig().getString("language", "en"));
    }

    @Override
    public boolean getContinueOnRestart() {
        return plugin.getConfig().getBoolean("continue-on-restart", false);
    }

    @Override
    public boolean isForceLoadExistingChunks() {
        return plugin.getConfig().getBoolean("force-load-existing-chunks", false);
    }

    @Override
    public boolean isSilent() {
        return plugin.getConfig().getBoolean("silent", false);
    }

    @Override
    public void setSilent(final boolean silent) {
        plugin.getConfig().set("silent", silent);
    }

    @Override
    public int getUpdateInterval() {
        return plugin.getConfig().getInt("update-interval", 1);
    }

    @Override
    public void setUpdateInterval(final int updateInterval) {
        plugin.getConfig().set("update-interval", updateInterval);
    }

    @Override
    public boolean isIoThrottleEnabled() {
        return plugin.getConfig().getBoolean("io-throttle", true);
    }

    @Override
    public double getThrottleTargetMspt() {
        return plugin.getConfig().getDouble("throttle-target-mspt", 150.0D);
    }

    @Override
    public long getThrottleMaxChunkMillis() {
        return plugin.getConfig().getLong("throttle-max-chunk-millis", 750L);
    }

    @Override
    public long getThrottleMaxQueuedWrites() {
        return plugin.getConfig().getLong("throttle-max-queued-writes", 800L);
    }

    @Override
    public long getThrottleMaxAddedChunks() {
        return plugin.getConfig().getLong("throttle-max-added-chunks", 0L);
    }

    @Override
    public long getThrottleMaxHeapPercent() {
        return plugin.getConfig().getLong("throttle-max-heap-percent", 85L);
    }

    @Override
    public long getThrottleTickBudgetMillis() {
        return plugin.getConfig().getLong("throttle-tick-budget-millis", 25L);
    }

    @Override
    public long getThrottlePlayerReserveMillis() {
        return plugin.getConfig().getLong("throttle-player-reserve-millis", 20L);
    }

    @Override
    public long getThrottleCeilingMillis() {
        return plugin.getConfig().getLong("throttle-ceiling-millis", 150L);
    }

    @Override
    public boolean isAutoPauseEnabled() {
        return plugin.getConfig().getBoolean("auto-pause-on-overload", true);
    }

    @Override
    public int getAutoPauseGraceSeconds() {
        return plugin.getConfig().getInt("auto-pause-grace-seconds", 120);
    }

    /**
     * CHANGED (2026-08-03, mod_support #9 follow-up): this platform now carries a
     * server-side CSLOD generator + store (see lod.CsLodExtractor / lod.LodSupport in this source
     * tree) -- there is just no renderer feed / client-streaming channel wired up to it YET (that is
     * a separate, later phase). So AUTO is no longer a lie here: a Bukkit / Paper / Folia process is
     * ALWAYS the dedicated-server case (there is no Bukkit singleplayer / integrated-server concept),
     * which is exactly the case the mod-loader decide() already treats as ON regardless of a local
     * renderer. Default is ON; an operator sets lod-enabled: false in config.yml to turn it off.
     * Accepts auto (default; behaves as ON on this platform), true, or false -- same tristate parsing
     * as every other platform.
     */
    @Override
    public LodMode getLodMode() {
        final String raw = plugin.getConfig().getString("lod-enabled", "auto");
        final LodMode mode = LodMode.parse(raw);
        if (mode == null) {
            plugin.getLogger().warning("Chunksmith: lod-enabled '" + raw + "' is not one of auto/true/false, using auto");
            return LodMode.AUTO;
        }
        return mode;
    }

    @Override
    public boolean isPregenSettleEnabled() {
        // The Bukkit platform does not manage chunk tickets itself, so there is nothing to hold open
        // and nothing for a settle window to do. Reported honestly rather than defaulted to true.
        return false;
    }

    @Override
    public long getPregenSettleDelayTicks() {
        return 0L;
    }

    @Override
    public int getPregenSettleRadius() {
        return 1;
    }

    @Override
    public long getPregenSettleMaxHeld() {
        // Nothing is ever held on this platform (see isPregenSettleEnabled), so there is nothing to cap.
        return 0L;
    }

    @Override
    public boolean isPregenSettleSupported() {
        return false;
    }

    // The three setters below are deliberately no-ops rather than throws. isPregenSettleSupported()
    // is false, so /cs settle refuses before it ever reaches them; a throw here would only turn a
    // future caller's oversight into a crash on a live server for a setting that does nothing anyway.

    @Override
    public void setPregenSettleEnabled(final boolean enabled) {
        // no-op: Bukkit does not manage chunk tickets, so there is no window to open
    }

    @Override
    public void setPregenSettleDelayTicks(final long ticks) {
        // no-op: see setPregenSettleEnabled
    }

    @Override
    public void setPregenSettleRadius(final int radius) {
        // no-op: see setPregenSettleEnabled
    }

    @Override
    public long getThrottleMaxLodQueue() {
        return plugin.getConfig().getLong("throttle-max-lod-queue", 512L);
    }

    @Override
    public long getDispatchMaxConcurrent() {
        return plugin.getConfig().getLong("dispatch-max-concurrent", 50L);
    }

    @Override
    public boolean isLodDhOverrideEnabled() {
        return false;
    }

    @Override
    public int getLodBackchannelPort() {
        return plugin.getConfig().getInt("lod-backchannel-port", 0);
    }

    @Override
    public void setLanguage(final String language) {
        plugin.getConfig().set("language", Input.checkLanguage(language));
        plugin.saveConfig();
    }

    @Override
    public void setContinueOnRestart(final boolean continueOnRestart) {
        plugin.getConfig().set("continue-on-restart", continueOnRestart);
        plugin.saveConfig();
    }

    @Override
    public void setForceLoadExistingChunks(final boolean forceLoadExistingChunks) {
        plugin.getConfig().set("force-load-existing-chunks", forceLoadExistingChunks);
        plugin.saveConfig();
    }

    @Override
    public void setIoThrottleEnabled(final boolean enabled) {
        plugin.getConfig().set("io-throttle", enabled);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleTargetMspt(final double mspt) {
        plugin.getConfig().set("throttle-target-mspt", mspt);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleMaxChunkMillis(final long millis) {
        plugin.getConfig().set("throttle-max-chunk-millis", millis);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleMaxQueuedWrites(final long writes) {
        plugin.getConfig().set("throttle-max-queued-writes", writes);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleMaxAddedChunks(final long chunks) {
        plugin.getConfig().set("throttle-max-added-chunks", chunks);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleMaxHeapPercent(final long percent) {
        plugin.getConfig().set("throttle-max-heap-percent", percent);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleTickBudgetMillis(final long millis) {
        plugin.getConfig().set("throttle-tick-budget-millis", millis);
        plugin.saveConfig();
    }

    @Override
    public void setThrottlePlayerReserveMillis(final long millis) {
        plugin.getConfig().set("throttle-player-reserve-millis", millis);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleCeilingMillis(final long millis) {
        plugin.getConfig().set("throttle-ceiling-millis", millis);
        plugin.saveConfig();
    }

    @Override
    public void setAutoPauseEnabled(final boolean enabled) {
        plugin.getConfig().set("auto-pause-on-overload", enabled);
        plugin.saveConfig();
    }

    @Override
    public void setAutoPauseGraceSeconds(final int seconds) {
        plugin.getConfig().set("auto-pause-grace-seconds", seconds);
        plugin.saveConfig();
    }

    @Override
    public void setPregenSettleMaxHeld(final long maxHeld) {
        // Accepted and persisted so the key round-trips, but this platform holds no tickets to cap.
        plugin.getConfig().set("pregen-settle-max-held", maxHeld);
        plugin.saveConfig();
    }

    @Override
    public void setThrottleMaxLodQueue(final long items) {
        plugin.getConfig().set("throttle-max-lod-queue", items);
        plugin.saveConfig();
    }

    @Override
    public void setDispatchMaxConcurrent(final long chunks) {
        plugin.getConfig().set("dispatch-max-concurrent", chunks);
        plugin.saveConfig();
    }

    @Override
    public void setLodMode(final LodMode mode) {
        plugin.getConfig().set("lod-enabled", mode.name().toLowerCase(java.util.Locale.ROOT));
        plugin.saveConfig();
    }

    @Override
    public void setLodDhOverrideEnabled(final boolean enabled) {
        plugin.getConfig().set("lod-dh-override", enabled);
        plugin.saveConfig();
    }

    @Override
    public void setLodBackchannelPort(final int port) {
        plugin.getConfig().set("lod-backchannel-port",
                (port < 1024 || port > 65535) ? 0 : port);
        plugin.saveConfig();
    }

    @Override
    public void reload() {
        plugin.reloadConfig();
    }
}
