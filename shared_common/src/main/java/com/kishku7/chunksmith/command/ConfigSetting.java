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

import com.kishku7.chunksmith.platform.Config;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ConfigSetting {

    public enum Kind {
        BOOLEAN(List.of("true", "false")),
        TRISTATE(List.of("auto", "true", "false")),
        INTEGER(List.of()),
        DECIMAL(List.of()),
        TEXT(List.of());

        private final List<String> completions;

        Kind(List<String> completions) {
            this.completions = completions;
        }

        public List<String> completions() {
            return completions;
        }
    }

    @FunctionalInterface
    public interface Writer {
        boolean write(Config config, String raw);
    }

    @FunctionalInterface
    public interface Explainer {
        String explain(Config config, String raw);
    }

    private final String name;
    private final Kind kind;
    private final Function<Config, String> reader;
    private final Writer writer;
    private final Predicate<Config> supported;
    private final Explainer explainer;

    ConfigSetting(final String name,
                  final Kind kind,
                  final Function<Config, String> reader,
                  final Writer writer,
                  final Predicate<Config> supported) {
        this(name, kind, reader, writer, supported, null);
    }

    ConfigSetting(final String name,
                  final Kind kind,
                  final Function<Config, String> reader,
                  final Writer writer,
                  final Predicate<Config> supported,
                  final Explainer explainer) {
        this.name = name;
        this.kind = kind;
        this.reader = reader;
        this.writer = writer;
        this.supported = supported;
        this.explainer = explainer;
    }

    public String explainRefusal(Config config, String raw) {
        return explainer == null ? null : explainer.explain(config, raw);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public String read(Config config) {
        return reader.apply(config);
    }

    public boolean write(Config config, String raw) {
        return writer.write(config, raw);
    }

    public boolean isSupported(Config config) {
        return supported.test(config);
    }
}
