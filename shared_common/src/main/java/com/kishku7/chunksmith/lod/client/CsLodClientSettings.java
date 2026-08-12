package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.util.Input;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * THE list of LOD-client settings {@code /cslod set} can reach -- one entry per key in
 * {@code config/chunksmith-lod.properties}.
 *
 * <p>House rule (2026-08-11): every setting in a config file is settable from a command. 3.2.4 satisfied
 * that for {@code config/chunksmith/config.json} via {@code ConfigSettings} + {@code /cs set}, and its
 * coverage test reflects over the JSON config model -- which is exactly why THESE two keys stayed
 * file-only for a release. <b>The enforcement was narrower than the rule.</b> A second config file needs
 * its own registry and its own coverage test, or a green test suite goes on meaning nothing about it.
 *
 * <p>Deliberately a MIRROR of {@code com.kishku7.chunksmith.command.ConfigSettings} rather than a reuse of
 * it: that one is keyed on a {@code Config} object handed in per call, because a server has one config per
 * server. This one is keyed on nothing -- the LOD client config is process-wide static state on the client
 * -- so sharing the type would mean inventing a {@code Config} the client does not have. Same shape, same
 * guarantees, no false abstraction.
 */
public final class CsLodClientSettings {

    /** What a value looks like -- drives tab completion and the "expected" half of an error. */
    public enum Kind {
        BOOLEAN(List.of("true", "false")),
        INTEGER(List.of());

        private final List<String> completions;

        Kind(final List<String> completions) {
            this.completions = completions;
        }

        public List<String> completions() {
            return completions;
        }
    }

    /**
     * One key, described well enough that the command can read it, write it and complete it without
     * knowing anything about the key itself.
     *
     * <p>A setting owns its own PARSING and reports whether the value was of the right SHAPE. Range
     * checking stays in {@link CsLodClientConfig}, which clamps on write -- so a clamp is reported
     * honestly as "set, to the clamped value" rather than as a rejection, and a typo is reported as a typo.
     */
    public static final class Setting {

        /** Applies a raw string. Returns false only if the string could not be understood. */
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

        /** One line, shown beside the value so the list is readable without opening the config file. */
        public String help() {
            return help;
        }

        /** The value currently in force -- read back from the config, never cached. */
        public String read() {
            return reader.get();
        }

        public boolean write(final String raw) {
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
                        final Optional<Long> value = Input.tryLong(raw);
                        // Out of int range is a shape error, not a clamp: 4000000000 is not a number of
                        // seconds anybody meant, and narrowing it silently would store its wrapped value.
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
                        final Optional<Boolean> value = Input.tryBoolean(raw);
                        value.ifPresent(CsLodClientConfig::setReinjectOnJoin);
                        return value.isPresent();
                    },
                    "send every LOD region to your renderer again on the next join, then set it back"));

    public static List<Setting> all() {
        return ALL;
    }

    /** Case-insensitive, because a hyphenated key is easy to half-remember. */
    public static Optional<Setting> find(final String name) {
        for (final Setting setting : ALL) {
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
