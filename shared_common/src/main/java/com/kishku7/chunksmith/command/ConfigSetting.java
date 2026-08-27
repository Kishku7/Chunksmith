package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.platform.Config;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One config-file key, described well enough that {@code /cs set} can read it, write it and complete it
 * without knowing anything about the key itself.
 *
 * <p>The alternative -- a command class per setting -- is what left nine settings with no command at all:
 * every new key silently opted out of being settable unless somebody remembered to write the class. Here a
 * key that is not in {@link ConfigSettings#all()} is a visible hole in one list, not an invisible omission
 * spread across a package.
 *
 * <p>A setting owns its own PARSING and reports whether the value was accepted. Range checking stays in the
 * config layer, which clamps on write; this layer only rejects values that are not of the right SHAPE
 * (a word where a number belongs). So a clamp is reported honestly as "set, to the clamped value" rather
 * than as a rejection, and a typo is reported as a typo.
 */
public final class ConfigSetting {

    /** What a value looks like -- drives tab completion and the "expected" half of an error. */
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

    /** Applies a raw string to the config. Returns false only if the string could not be understood. */
    @FunctionalInterface
    public interface Writer {
        boolean write(Config config, String raw);
    }

    /**
     * Explains a refusal in the setting's own terms, or null to use the generic message.
     *
     * <p>The generic message can only report the KIND a value should have been, which is right
     * for a typo and actively misleading for a value that is the right shape and still wrong --
     * a port number that happens to be the game's own port is the standing example.
     */
    @FunctionalInterface
    public interface Explainer {
        /** @return why this raw value was refused, or null if there is nothing specific to say */
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

    /** Why this value was refused, in this setting's own terms, or null for the generic message. */
    public String explainRefusal(final Config config, final String raw) {
        return explainer == null ? null : explainer.explain(config, raw);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** The value currently in force -- read back from the config, never cached. */
    public String read(final Config config) {
        return reader.apply(config);
    }

    public boolean write(final Config config, final String raw) {
        return writer.write(config, raw);
    }

    /**
     * Whether this setting does anything on this platform. False for the settle keys on Bukkit, which
     * does not manage chunk tickets -- reporting that is better than accepting a value that is ignored.
     */
    public boolean isSupported(final Config config) {
        return supported.test(config);
    }
}
