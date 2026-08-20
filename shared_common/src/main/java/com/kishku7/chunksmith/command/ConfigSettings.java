package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.LodMode;
import com.kishku7.chunksmith.util.Input;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * THE list of settings {@code /cs set} can reach -- one entry per key in the config file.
 *
 * <p>House rule (2026-08-11): every setting in the config file is settable from a command. This list
 * is where that rule is kept honest. Adding a key to the config and not to this list is the bug; keeping
 * them adjacent in one file makes the omission obvious in review instead of invisible.
 *
 * <p>{@code version} is deliberately absent. It is the config SCHEMA number, not an operator setting --
 * letting someone set it would invite a file that claims to be a shape it is not.
 */
public final class ConfigSettings {

    private ConfigSettings() {
    }

    private static final List<ConfigSetting> ALL = List.of(
            of("language", ConfigSetting.Kind.TEXT,
                    Config::getLanguage,
                    (config, raw) -> {
                        // checkLanguage falls back to "en" for anything it does not ship, so an
                        // unknown code would silently become English. Compare first and refuse,
                        // rather than pretend a typo was a language.
                        if (!Input.checkLanguage(raw).equalsIgnoreCase(raw)) {
                            return false;
                        }
                        config.setLanguage(raw);
                        return true;
                    }),
            bool("continueOnRestart", Config::getContinueOnRestart, Config::setContinueOnRestart),
            bool("forceLoadExistingChunks", Config::isForceLoadExistingChunks, Config::setForceLoadExistingChunks),
            bool("silent", Config::isSilent, Config::setSilent),
            integer("updateInterval", config -> (long) config.getUpdateInterval(),
                    (config, value) -> config.setUpdateInterval((int) value)),
            bool("ioThrottle", Config::isIoThrottleEnabled, Config::setIoThrottleEnabled),
            of("throttleTargetMspt", ConfigSetting.Kind.DECIMAL,
                    config -> String.valueOf(config.getThrottleTargetMspt()),
                    (config, raw) -> {
                        final Optional<Double> value = Input.tryDouble(raw);
                        value.ifPresent(config::setThrottleTargetMspt);
                        return value.isPresent();
                    }),
            integer("throttleMaxChunkMillis", Config::getThrottleMaxChunkMillis, Config::setThrottleMaxChunkMillis),
            integer("throttleMaxQueuedWrites", Config::getThrottleMaxQueuedWrites, Config::setThrottleMaxQueuedWrites),
            integer("throttleMaxLodQueue", Config::getThrottleMaxLodQueue, Config::setThrottleMaxLodQueue),
            integer("throttleMaxAddedChunks", Config::getThrottleMaxAddedChunks, Config::setThrottleMaxAddedChunks),
            integer("throttleMaxHeapPercent", Config::getThrottleMaxHeapPercent, Config::setThrottleMaxHeapPercent),
            bool("autoPauseOnOverload", Config::isAutoPauseEnabled, Config::setAutoPauseEnabled),
            integer("autoPauseGraceSeconds", config -> (long) config.getAutoPauseGraceSeconds(),
                    (config, value) -> config.setAutoPauseGraceSeconds((int) value)),
            of("lodEnabled", ConfigSetting.Kind.TRISTATE,
                    config -> config.getLodMode().name().toLowerCase(Locale.ROOT),
                    (config, raw) -> {
                        final LodMode mode = LodMode.parse(raw);
                        if (mode == null) {
                            return false;
                        }
                        config.setLodMode(mode);
                        return true;
                    }),
            bool("lodDhOverride", Config::isLodDhOverrideEnabled, Config::setLodDhOverrideEnabled),
            settle(bool("pregenSettle", Config::isPregenSettleEnabled, Config::setPregenSettleEnabled)),
            settle(integer("pregenSettleDelayTicks",
                    Config::getPregenSettleDelayTicks, Config::setPregenSettleDelayTicks)),
            settle(integer("pregenSettleRadius",
                    config -> (long) config.getPregenSettleRadius(),
                    (config, value) -> config.setPregenSettleRadius((int) value))),
            settle(integer("pregenSettleMaxHeld",
                    Config::getPregenSettleMaxHeld, Config::setPregenSettleMaxHeld))
    );

    public static List<ConfigSetting> all() {
        return ALL;
    }

    /** Case-insensitive, because nobody types {@code forceLoadExistingChunks} correctly the first time. */
    public static Optional<ConfigSetting> find(final String name) {
        for (final ConfigSetting setting : ALL) {
            if (setting.name().equalsIgnoreCase(name)) {
                return Optional.of(setting);
            }
        }
        return Optional.empty();
    }

    public static List<String> names() {
        return ALL.stream().map(ConfigSetting::name).toList();
    }

    // --- builders -------------------------------------------------------------------------------

    private interface BoolGetter {
        boolean get(Config config);
    }

    private interface BoolSetter {
        void set(Config config, boolean value);
    }

    private interface LongGetter {
        long get(Config config);
    }

    private interface LongSetter {
        void set(Config config, long value);
    }

    private static ConfigSetting of(final String name,
                                    final ConfigSetting.Kind kind,
                                    final java.util.function.Function<Config, String> reader,
                                    final ConfigSetting.Writer writer) {
        return new ConfigSetting(name, kind, reader, writer, config -> true);
    }

    private static ConfigSetting bool(final String name, final BoolGetter getter, final BoolSetter setter) {
        return new ConfigSetting(name, ConfigSetting.Kind.BOOLEAN,
                config -> String.valueOf(getter.get(config)),
                (config, raw) -> {
                    final Optional<Boolean> value = Input.tryBoolean(raw);
                    value.ifPresent(v -> setter.set(config, v));
                    return value.isPresent();
                },
                config -> true);
    }

    private static ConfigSetting integer(final String name, final LongGetter getter, final LongSetter setter) {
        return new ConfigSetting(name, ConfigSetting.Kind.INTEGER,
                config -> String.valueOf(getter.get(config)),
                (config, raw) -> {
                    final Optional<Long> value = Input.tryLong(raw);
                    value.ifPresent(v -> setter.set(config, v));
                    return value.isPresent();
                },
                config -> true);
    }

    /** Marks a setting as one Bukkit cannot honour, so the command can say so instead of lying. */
    private static ConfigSetting settle(final ConfigSetting base) {
        return new ConfigSetting(base.name(), base.kind(),
                base::read, base::write, Config::isPregenSettleSupported);
    }
}
