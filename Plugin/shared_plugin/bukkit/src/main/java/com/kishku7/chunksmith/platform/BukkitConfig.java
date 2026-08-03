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
    public long getThrottleMaxLodQueue() {
        return plugin.getConfig().getLong("throttle-max-lod-queue", 512L);
    }

    @Override
    public boolean isLodDhOverrideEnabled() {
        return false;
    }

    @Override
    public void reload() {
        plugin.reloadConfig();
    }
}
