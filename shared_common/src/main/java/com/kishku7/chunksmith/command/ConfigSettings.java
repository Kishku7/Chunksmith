/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
                        Optional<Double> value = Input.tryDouble(raw);
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
                        LodMode mode = LodMode.parse(raw);
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
                        Optional<Long> value = Input.tryLong(raw);
                        if (value.isEmpty()) {
                            return false;
                        }
                        long asked = value.get();
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
                        OptionalInt game = CsLodControl.gamePort();
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
            // No CsLodControl.apply() here, unlike the port beside it: nothing is bound to a budget.
            // It is read when the next index is built, so the next answer already honours it.
            integer("lodIndexBudgetMb", Config::getLodIndexBudgetMb, Config::setLodIndexBudgetMb),
            worldEnter(bool("worldEnterPregen",
                    Config::isWorldEnterPregenEnabled, Config::setWorldEnterPregenEnabled)),
            worldEnter(integer("worldEnterPregenRadius",
                    Config::getWorldEnterPregenRadius, Config::setWorldEnterPregenRadius)),
            // Different validators on purpose. A BIND address may be a wildcard (0.0.0.0 = every
            // interface); an ADVERTISED one may not, because a client told to connect to 0.0.0.0
            // has been told nothing at all.
            host("lodBackchannelBindAddress",
                    Config::getLodBackchannelBindAddress, Config::setLodBackchannelBindAddress,
                    Input::checkHost),
            host("lodBackchannelHost",
                    Config::getLodBackchannelHost, Config::setLodBackchannelHost,
                    Input::checkAdvertisedHost),
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

    public static Optional<ConfigSetting> find(String name) {
        for (ConfigSetting setting : ALL) {
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
                    Optional<Long> asked = Input.tryLong(raw);
                    if (asked.isEmpty()) {
                        return null;   // a word where a number goes: the generic message is right
                    }
                    long value = asked.get();
                    OptionalInt game = CsLodControl.gamePort();
                    if (value != 0 && game.isPresent() && value == game.getAsInt()) {
                        return "that is the port the game itself is listening on. Pick another"
                                + " port, or use 0 to derive it (" + (game.getAsInt() + 1) + ").";
                    }
                    return null;
                });
    }

    /** How an operator clears a host key from a chat command, where an empty argument cannot be typed. */
    private static final String CLEAR = "none";

    /**
     * A host or IP key that takes effect immediately.
     *
     * <p>Both of these change where the backchannel listens or where clients are sent, and both
     * exist for an operator whose address has moved under them -- so, like the port beside them,
     * they rebind and re-advertise on the spot rather than on the next restart. A setting that only
     * applies after a restart would solve their problem in theory and not in practice.
     */
    private static ConfigSetting host(String name,
                                      Function<Config, String> getter,
                                      TextSetter setter,
                                      Function<String, String> validator) {
        return new ConfigSetting(name, ConfigSetting.Kind.TEXT,
                config -> {
                    String value = getter.apply(config);
                    // Read back as the word the operator would type to get here, not as a blank.
                    return value == null || value.isEmpty() ? CLEAR : value;
                },
                (config, raw) -> {
                    String asked = raw == null ? "" : raw.trim();
                    if (asked.isEmpty() || CLEAR.equalsIgnoreCase(asked)) {
                        setter.set(config, "");
                        CsLodControl.apply();
                        return true;
                    }
                    // Refuse rather than store the empty string the validator would fall back to.
                    // Silently turning a typo into "unset" would answer "done" and change nothing.
                    if (validator.apply(asked).isEmpty()) {
                        return false;
                    }
                    setter.set(config, asked);
                    CsLodControl.apply();
                    return true;
                },
                config -> true);
    }

    private interface TextSetter {
        void set(Config config, String value);
    }

    private static ConfigSetting of(final String name,
                                    final ConfigSetting.Kind kind,
                                    final Function<Config, String> reader,
                                    final ConfigSetting.Writer writer) {
        return new ConfigSetting(name, kind, reader, writer, config -> true);
    }

    private static ConfigSetting bool(String name, BoolGetter getter, BoolSetter setter) {
        return new ConfigSetting(name, ConfigSetting.Kind.BOOLEAN,
                config -> String.valueOf(getter.get(config)),
                (config, raw) -> {
                    Optional<Boolean> value = Input.tryBoolean(raw);
                    value.ifPresent(v -> setter.set(config, v));
                    return value.isPresent();
                },
                config -> true);
    }

    private static ConfigSetting integer(String name, LongGetter getter, LongSetter setter) {
        return new ConfigSetting(name, ConfigSetting.Kind.INTEGER,
                config -> String.valueOf(getter.get(config)),
                (config, raw) -> {
                    Optional<Long> value = Input.tryLong(raw);
                    value.ifPresent(v -> setter.set(config, v));
                    return value.isPresent();
                },
                config -> true);
    }

    /**
     * Hides a world-enter setting on platforms that cannot run it.
     *
     * <p>Same shape as {@link #settle}: the Bukkit plugin has no world-entry moment, so offering
     * `/cs set worldEnterPregen true` there would accept a value and change nothing.
     */
    private static ConfigSetting worldEnter(ConfigSetting base) {
        return new ConfigSetting(base.name(), base.kind(),
                base::read, base::write, Config::isWorldEnterPregenSupported);
    }

    private static ConfigSetting settle(ConfigSetting base) {
        return new ConfigSetting(base.name(), base.kind(),
                base::read, base::write, Config::isPregenSettleSupported);
    }
}
