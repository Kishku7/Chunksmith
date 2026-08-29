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

package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.util.Input;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The house rule is that every setting in a config file is settable from a command. 3.2.4
 * satisfied it for {@code config/chunksmith/config.json} via {@code ConfigSettings} + {@code
 * /cs set}, and that coverage test reflects over the JSON config model -- which is exactly why
 * THESE two keys stayed file-only for a release. A second config file needs its own registry
 * and its own coverage test.
 */
public final class CsLodClientSettings {

    public enum Kind {
        BOOLEAN(List.of("true", "false")),
        INTEGER(List.of());

        private final List<String> completions;

        Kind(List<String> completions) {
            this.completions = completions;
        }

        public List<String> completions() {
            return completions;
        }
    }

    public static final class Setting {

        @FunctionalInterface
        public interface Writer {
            boolean write(String raw);
        }

        private final String name;
        private final Kind kind;
        private final Supplier<String> reader;
        private final Writer writer;
        private final String help;

        Setting(final String name,
                final Kind kind,
                final Supplier<String> reader,
                final Writer writer,
                final String help) {
            this.name = name;
            this.kind = kind;
            this.reader = reader;
            this.writer = writer;
            this.help = help;
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        public String help() {
            return help;
        }

        public String read() {
            return reader.get();
        }

        public boolean write(String raw) {
            return writer.write(raw);
        }
    }

    private CsLodClientSettings() {
    }

    private static final List<Setting> ALL = List.of(
            new Setting(CsLodClientConfig.KEY_SYNC_SECONDS,
                    Kind.INTEGER,
                    () -> Integer.toString(CsLodClientConfig.syncIntervalSeconds()),
                    raw -> {
                        Optional<Long> value = Input.tryLong(raw);
                        if (value.isEmpty() || value.get() > Integer.MAX_VALUE
                                || value.get() < Integer.MIN_VALUE) {
                            return false;
                        }
                        CsLodClientConfig.setSyncSeconds(value.get().intValue());
                        return true;
                    },
                    "how often to ask the server whether its LOD store changed, in seconds (minimum "
                            + CsLodClientConfig.MIN_SYNC_SECONDS + ")"),
            new Setting(CsLodClientConfig.KEY_REINJECT,
                    Kind.BOOLEAN,
                    () -> Boolean.toString(CsLodClientConfig.reinjectOnJoin()),
                    raw -> {
                        Optional<Boolean> value = Input.tryBoolean(raw);
                        value.ifPresent(CsLodClientConfig::setReinjectOnJoin);
                        return value.isPresent();
                    },
                    "send every LOD region to your renderer again on the next join, then set it back"));

    public static List<Setting> all() {
        return ALL;
    }

    public static Optional<Setting> find(String name) {
        for (Setting setting : ALL) {
            if (setting.name().equalsIgnoreCase(name)) {
                return Optional.of(setting);
            }
        }
        return Optional.empty();
    }

    public static List<String> names() {
        return ALL.stream().map(Setting::name).toList();
    }
}
