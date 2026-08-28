package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.lod.net.CsLodControl;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.LodMode;
import com.kishku7.chunksmith.util.Input;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

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
            integer("dispatchMaxConcurrent", Config::getDispatchMaxConcurrent, Config::setDispatchMaxConcurrent),
            integer("throttleMaxAddedChunks", Config::getThrottleMaxAddedChunks, Config::setThrottleMaxAddedChunks),
            integer("throttleMaxHeapPercent", Config::getThrottleMaxHeapPercent, Config::setThrottleMaxHeapPercent),
            integer("throttleTickBudgetMillis", Config::getThrottleTickBudgetMillis, Config::setThrottleTickBudgetMillis),
            integer("throttlePlayerReserveMillis", Config::getThrottlePlayerReserveMillis, Config::setThrottlePlayerReserveMillis),
            integer("throttleCeilingMillis", Config::getThrottleCeilingMillis, Config::setThrottleCeilingMillis),
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
            port("lodBackchannelPort", ConfigSetting.Kind.INTEGER,
                    config -> String.valueOf(config.getLodBackchannelPort()),
                    (config, raw) -> {
                        final Optional<Long> value = Input.tryLong(raw);
                        if (value.isEmpty()) {
                            return false;
                        }
                        final long asked = value.get();
                        // Out of INT range at all is a typo, not a port. Casting it would wrap and
                        // store something nobody asked for.
                        if (asked < Integer.MIN_VALUE || asked > Integer.MAX_VALUE) {
                            return false;
                        }
                        // Refuse the game's own port here, before it is stored. The bind refuses it
                        // too, but a bind happens after the write: accepting it would save a value
                        // that kills the backchannel, answer "done", and keep it dead across every
                        // restart until somebody thought to look. Found by driving this on a live
                        // server, not by reading it.
                        final OptionalInt game = CsLodControl.gamePort();
                        if (asked != 0 && game.isPresent() && asked == game.getAsInt()) {
                            return false;
                        }
                        config.setLodBackchannelPort((int) asked);
                        // Take effect NOW. The whole point of the key is to help an operator who
                        // cannot casually restart; a port that only moves on the next boot would
                        // solve their problem in theory and not in practice.
                        CsLodControl.apply();
                        return true;
                    }),
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

    private static ConfigSetting port(final String name,
                                      final ConfigSetting.Kind kind,
                                      final Function<Config, String> reader,
                                      final ConfigSetting.Writer writer) {
        return new ConfigSetting(name, kind, reader, writer, config -> true,
                (config, raw) -> {
                    final Optional<Long> asked = Input.tryLong(raw);
                    if (asked.isEmpty()) {
                        return null;   // a word where a number goes: the generic message is right
                    }
                    final long value = asked.get();
                    final OptionalInt game = CsLodControl.gamePort();
                    if (value != 0 && game.isPresent() && value == game.getAsInt()) {
                        return "that is the port the game itself is listening on. Pick another"
                                + " port, or use 0 to derive it (" + (game.getAsInt() + 1) + ").";
                    }
                    return null;
                });
    }

    private static ConfigSetting of(final String name,
                                    final ConfigSetting.Kind kind,
                                    final Function<Config, String> reader,
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

    private static ConfigSetting settle(final ConfigSetting base) {
        return new ConfigSetting(base.name(), base.kind(),
                base::read, base::write, Config::isPregenSettleSupported);
    }
}
