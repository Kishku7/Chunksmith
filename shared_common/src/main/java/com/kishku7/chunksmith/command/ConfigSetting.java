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

        Kind(final List<String> completions) {
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

    public String explainRefusal(final Config config, final String raw) {
        return explainer == null ? null : explainer.explain(config, raw);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public String read(final Config config) {
        return reader.apply(config);
    }

    public boolean write(final Config config, final String raw) {
        return writer.write(config, raw);
    }

    public boolean isSupported(final Config config) {
        return supported.test(config);
    }
}
