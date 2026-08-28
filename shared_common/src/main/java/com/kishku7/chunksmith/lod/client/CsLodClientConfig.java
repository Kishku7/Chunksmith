package com.kishku7.chunksmith.lod.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The LOD client's own settings: how often to ask the server whether anything changed, and whether to
 * re-inject everything on the next join.
 *
 * <p>No settings SCREEN, deliberately -- a config UI is 3.2's problem. This is a plain
 * {@code config/chunksmith-lod.properties} the client writes with its defaults and comments on first run,
 * discoverable by anyone who opens the folder and editable without a mod menu.
 *
 * <p><b>Both settings are also reachable from {@code /cslod set}</b> (3.3.0): a setting you can only change
 * by editing a file and restarting is not a setting on a running game. The command writes through
 * {@link #setSyncSeconds(int)} / {@link #setReinjectOnJoin(boolean)}, which apply the value to the running
 * client AND save the file, so the two can never disagree. {@link CsLodClientSettings} is the registry that
 * exposes them, and a coverage test fails by name if a key here is not in it.
 *
 * <p><b>The floor is enforced in CODE, not in the file.</b> The value is clamped to
 * {@link #MIN_SYNC_SECONDS} every time it is read AND on the way in when set by command, so nothing
 * downstream can see a smaller number whatever the file says.
 */
public final class CsLodClientConfig {

    /** The file, under the game's {@code config/} directory. */
    public static final String FILE_NAME = "chunksmith-lod.properties";

    /** How often to ask the server whether anything changed. */
    public static final String KEY_SYNC_SECONDS = "sync-interval-seconds";

    /**
     * Throw away what we remember having injected, and inject it all again.
     *
     * <p>The escape hatch for the one case the injected index cannot detect: the player emptying the
     * renderer's own database underneath it. voxy's storage and DH's sqlite are theirs, and nothing we can
     * read says "this was reset", so our record would honestly describe data that is gone and we would skip
     * it forever. Set it true, join once, set it back; {@code /cslod set reinject-on-join true} does that
     * without leaving the game, and deleting the {@code .injected} files does the same.
     */
    public static final String KEY_REINJECT = "reinject-on-join";

    /**
     * How often the client asks "has anything changed?" by default. Five minutes.
     *
     * <p>The poll costs 22 bytes out and 34 bytes back and opens no file on either side, so the interval is
     * not chosen to protect the server: it is chosen so a player standing in a base sees their horizon
     * extend every few minutes while an operator's pregen fills the world in behind them.
     */
    public static final int DEFAULT_SYNC_SECONDS = 300;

    /**
     * The floor. Thirty seconds.
     *
     * <p>Not a guess: it is the smallest interval at which the sync cannot become the problem it solves. One
     * poll is one readdir plus one stat per in-range region on a background thread -- for a 340-region store
     * with a 4-region radius that is ~86 syscalls and zero bytes of file content. At 30 s, a hundred clients
     * cost the server about three of those per second.
     */
    public static final int MIN_SYNC_SECONDS = 30;

    private static final String COMMENT =
            " Chunksmith LOD client.\n"
            + "\n"
            + " Both settings can also be changed in-game with /cslod set <name> <value>, which applies\n"
            + " the value immediately and rewrites this file. Editing by hand still works; the file is\n"
            + " read at startup.\n"
            + "\n"
            + " " + KEY_SYNC_SECONDS + " -- how often (in SECONDS) to ask the server whether its LOD store\n"
            + " has changed. The check itself is two tiny messages; a full index is only pulled when the\n"
            + " answer is 'yes'. This is what lets a player who is STANDING STILL pick up terrain from a\n"
            + " pregen that is still running, with no relog and no need to go for a walk.\n"
            + "\n"
            + " Default " + DEFAULT_SYNC_SECONDS + ". Values below " + MIN_SYNC_SECONDS
            + " are clamped to " + MIN_SYNC_SECONDS + ".\n"
            + "\n"
            + " " + KEY_REINJECT + " -- normally Chunksmith remembers which LOD regions it has already\n"
            + " given to your renderer, so joining a world does not re-send terrain voxy or Distant\n"
            + " Horizons already has. Set this to true for ONE join if you have deleted or reset your\n"
            + " renderer's own data and want everything sent again. Default false.";

    private static volatile int syncSeconds = DEFAULT_SYNC_SECONDS;
    private static volatile boolean reinject;
    private static volatile boolean loaded;

    /**
     * Where the file lives, remembered from {@link #load} so a later {@code /cslod set} can save without
     * being handed the config directory again. Null until load() has run -- on a dedicated server this class
     * is never touched. A save with no path applies the value in memory and writes nothing.
     */
    private static volatile Path file;

    private CsLodClientConfig() {
    }

    /**
     * Read the config, writing it with defaults if it is not there yet.
     *
     * <p>Every failure mode ends at the default: an unreadable file, a missing key, a value that is not a
     * number. A config problem must never be the reason a player gets no terrain.
     *
     * @return the message to log -- one line, said once, and it names the clamp when the clamp bit
     */
    public static synchronized String load(final Path configDir) {
        final Path path = configDir.resolve(FILE_NAME);
        file = path;
        final Properties properties = new Properties();
        boolean present = false;
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
                present = true;
            } catch (final IOException e) {
                loaded = true;
                syncSeconds = DEFAULT_SYNC_SECONDS;
                reinject = false;
                return "could not read " + FILE_NAME + " (" + e + "); using the defaults";
            }
        }

        // Anything not literally "true" is false: a misspelt value must not silently turn a one-shot
        // recovery switch into permanent behaviour.
        reinject = Boolean.parseBoolean(
                properties.getProperty(KEY_REINJECT, "false").trim());

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
            save();
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
        if (reinject) {
            return "syncing with the server every " + clamped + "s; " + KEY_REINJECT
                    + " is ON, so every LOD region will be sent to your renderer again this session"
                    + " (set it back to false once your terrain is back)";
        }
        return "syncing with the server every " + clamped + "s";
    }

    /** The interval, in MILLISECONDS, already clamped. */
    public static long syncIntervalMillis() {
        return syncSeconds * 1000L;
    }

    public static int syncIntervalSeconds() {
        return syncSeconds;
    }

    public static boolean reinjectOnJoin() {
        return reinject;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Set the sync interval and save it. Clamped ON WRITE as well as on read, so the file can never hold a
     * number the client would refuse to honour.
     *
     * @return the value actually stored, which is what the command reports rather than echoing the input
     */
    public static synchronized int setSyncSeconds(final int seconds) {
        syncSeconds = clamp(seconds);
        save();
        return syncSeconds;
    }

    /** Set the one-shot re-injection switch and save it. See {@link #KEY_REINJECT}. */
    public static synchronized void setReinjectOnJoin(final boolean value) {
        reinject = value;
        save();
    }

    /** The floor, applied to any value from any source. Public so the unit test asserts the SAME function. */
    public static int clamp(final int seconds) {
        return Math.max(MIN_SYNC_SECONDS, seconds);
    }

    /** Test seam: set the interval directly, as though it had been read from a file. */
    static void setForTesting(final int seconds) {
        syncSeconds = clamp(seconds);
        loaded = true;
    }

    static void setReinjectForTesting(final boolean value) {
        reinject = value;
    }

    /**
     * Write the values CURRENTLY in force -- not the defaults. A failure is swallowed: the value is already
     * in effect in memory, and a player who cannot write their own config directory has a bigger problem.
     */
    private static void save() {
        final Path path = file;
        if (path == null) {
            return;
        }
        final Properties out = new Properties();
        out.setProperty(KEY_SYNC_SECONDS, Integer.toString(syncSeconds));
        out.setProperty(KEY_REINJECT, Boolean.toString(reinject));
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream stream = Files.newOutputStream(path)) {
                out.store(stream, COMMENT);
            }
        } catch (final IOException ignored) {
            // See the javadoc: the defaults are already in effect and nothing downstream depends on the
            // file having been written.
        }
    }
}
