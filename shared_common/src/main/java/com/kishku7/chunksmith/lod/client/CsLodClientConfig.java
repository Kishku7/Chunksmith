package com.kishku7.chunksmith.lod.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The LOD client's own settings. One knob today: how often to ask the server whether anything changed.
 *
 * <p><b>There is no settings SCREEN, and that is deliberate</b> -- a config UI is 3.2's problem. This is a
 * plain {@code config/chunksmith-lod.properties} that the client writes with its defaults and comments on
 * first run, so the knob is discoverable by anyone who opens the folder, and editable without a mod menu.
 *
 * <p><b>The floor is enforced in CODE, not in the file.</b> A config value is a suggestion from whoever last
 * edited the file, and "sync-interval-seconds=1" would turn the self-healing sync into a poll storm against
 * a server that is trying to run a pregen -- the exact class of problem this whole release is fixing. So the
 * value is clamped to {@link #MIN_SYNC_SECONDS} on the way out of this class, every time it is read. Nothing
 * downstream can ever see a smaller number, whatever the file says, and a clamped value is announced once so
 * the person who set it understands why it is not being honoured.
 */
public final class CsLodClientConfig {

    /** The file, under the game's {@code config/} directory. */
    public static final String FILE_NAME = "chunksmith-lod.properties";

    /** The one key. */
    public static final String KEY_SYNC_SECONDS = "sync-interval-seconds";

    /**
     * How often the client asks "has anything changed?" by default.
     *
     * <p>Five minutes. The poll costs 22 bytes out and 34 bytes back and does not touch a file's contents on
     * either side, so the interval is not chosen to protect the server -- it is chosen because it is the
     * right feel: a player standing in a base while an operator's pregen fills the world in behind them sees
     * their horizon extend every few minutes, without anything ever having to be told to relog.
     */
    public static final int DEFAULT_SYNC_SECONDS = 300;

    /**
     * The floor. Thirty seconds.
     *
     * <p>Not a guess: it is the smallest interval at which the sync cannot become the problem it solves. One
     * poll is one readdir plus one stat per in-range region on a background thread -- for a 340-region store
     * with a 4-region radius that is ~86 syscalls and zero bytes of file content. At 30 s, a hundred clients
     * cost the server about three of those per second. Below 30 s the poll starts to be worth thinking about,
     * and there is no reader for whom 20 s is meaningfully better than 30 s.
     */
    public static final int MIN_SYNC_SECONDS = 30;

    private static final String COMMENT =
            " Chunksmith LOD client.\n"
            + "\n"
            + " " + KEY_SYNC_SECONDS + " -- how often (in SECONDS) to ask the server whether its LOD store\n"
            + " has changed. The check itself is two tiny messages; a full index is only pulled when the\n"
            + " answer is 'yes'. This is what lets a player who is STANDING STILL pick up terrain from a\n"
            + " pregen that is still running, with no relog and no need to go for a walk.\n"
            + "\n"
            + " Default " + DEFAULT_SYNC_SECONDS + ". Values below " + MIN_SYNC_SECONDS
            + " are clamped to " + MIN_SYNC_SECONDS + ".";

    private static volatile int syncSeconds = DEFAULT_SYNC_SECONDS;
    private static volatile boolean loaded;

    private CsLodClientConfig() {
    }

    /**
     * Read the config (writing it with defaults if it is not there yet).
     *
     * <p>Every failure mode ends at the default: an unreadable file, a missing key, a value that is not a
     * number. A config problem must never be the reason a player gets no terrain.
     *
     * @param configDir the game's {@code config} directory
     * @return the message to log -- one line, said once, and it names the clamp when the clamp bit
     */
    public static synchronized String load(final Path configDir) {
        final Path file = configDir.resolve(FILE_NAME);
        final Properties properties = new Properties();
        boolean present = false;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
                present = true;
            } catch (final IOException e) {
                loaded = true;
                syncSeconds = DEFAULT_SYNC_SECONDS;
                return "could not read " + FILE_NAME + " (" + e + "); using the defaults";
            }
        }

        final String raw = properties.getProperty(KEY_SYNC_SECONDS);
        int requested = DEFAULT_SYNC_SECONDS;
        boolean unparseable = false;
        if (raw != null && !raw.isBlank()) {
            try {
                requested = Integer.parseInt(raw.trim());
            } catch (final NumberFormatException e) {
                unparseable = true;
            }
        }

        final int clamped = clamp(requested);
        syncSeconds = clamped;
        loaded = true;

        if (!present) {
            write(file);
            return "wrote " + FILE_NAME + " with the defaults (sync every " + clamped + "s)";
        }
        if (unparseable) {
            return FILE_NAME + ": '" + raw + "' is not a number; syncing every "
                    + DEFAULT_SYNC_SECONDS + "s";
        }
        if (clamped != requested) {
            return FILE_NAME + ": " + KEY_SYNC_SECONDS + "=" + requested + " is below the "
                    + MIN_SYNC_SECONDS + "s minimum; syncing every " + clamped + "s instead";
        }
        return "syncing with the server every " + clamped + "s";
    }

    /**
     * The interval, in MILLISECONDS, already clamped. This is the only way the rest of the mod may obtain
     * it -- there is no accessor that can return an unclamped value.
     */
    public static long syncIntervalMillis() {
        return syncSeconds * 1000L;
    }

    /** The interval in seconds, already clamped. */
    public static int syncIntervalSeconds() {
        return syncSeconds;
    }

    /** Has {@link #load} run? Only used to keep the tick loop from polling before we know the interval. */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * The floor, applied to any value from any source. Public so the unit test asserts the SAME function the
     * mod uses, rather than a re-implementation of it.
     */
    public static int clamp(final int seconds) {
        return Math.max(MIN_SYNC_SECONDS, seconds);
    }

    /** Test seam: set the interval directly, as though it had been read from a file. */
    static void setForTesting(final int seconds) {
        syncSeconds = clamp(seconds);
        loaded = true;
    }

    private static void write(final Path file) {
        final Properties out = new Properties();
        out.setProperty(KEY_SYNC_SECONDS, Integer.toString(DEFAULT_SYNC_SECONDS));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream stream = Files.newOutputStream(file)) {
                out.store(stream, COMMENT);
            }
        } catch (final IOException ignored) {
            // We could not write a config file. The defaults are already in effect; a player who cannot
            // write to their own config directory has a bigger problem than our sync interval.
        }
    }
}
