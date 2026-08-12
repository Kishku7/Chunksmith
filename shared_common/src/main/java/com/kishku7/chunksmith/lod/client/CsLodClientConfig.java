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
 * <p><b>There is no settings SCREEN, and that is deliberate</b> -- a config UI is 3.2's problem. This is a
 * plain {@code config/chunksmith-lod.properties} that the client writes with its defaults and comments on
 * first run, so the knob is discoverable by anyone who opens the folder, and editable without a mod menu.
 *
 * <p><b>Both settings are also reachable from {@code /cslod set}</b> (3.3.0), which is what the house rule
 * requires: a setting you can only change by editing a file and restarting is not a setting on a running
 * game. The command writes through {@link #setSyncSeconds(int)} / {@link #setReinjectOnJoin(boolean)},
 * which apply the value to the running client AND save the file, so the two can never disagree. The
 * registry that exposes them is {@link CsLodClientSettings}, and a coverage test fails by name if a key
 * here is not in it.
 *
 * <p><b>The floor is enforced in CODE, not in the file.</b> A config value is a suggestion from whoever last
 * edited the file, and "sync-interval-seconds=1" would turn the self-healing sync into a poll storm against
 * a server that is trying to run a pregen -- the exact class of problem this whole release is fixing. So the
 * value is clamped to {@link #MIN_SYNC_SECONDS} on the way out of this class, every time it is read, AND on
 * the way in when it is set by command. Nothing downstream can ever see a smaller number, whatever the file
 * says, and a clamped value is announced once so the person who set it understands why it is not being
 * honoured.
 */
public final class CsLodClientConfig {

    /** The file, under the game's {@code config/} directory. */
    public static final String FILE_NAME = "chunksmith-lod.properties";

    /** How often to ask the server whether anything changed. */
    public static final String KEY_SYNC_SECONDS = "sync-interval-seconds";

    /**
     * Throw away what we remember having injected, and inject it all again.
     *
     * <p>The escape hatch for the one case the injected-index cannot detect on its own. That index records
     * which regions we handed to a renderer, and it survives across sessions -- which is what stops every
     * world join re-pushing terrain the renderer already has. It notices a CHANGED region (the token moved)
     * and it notices a changed RENDERER SET (the epoch moved). What it cannot notice is the player emptying
     * the renderer's own database underneath it: voxy's storage and DH's sqlite are theirs, not ours, and
     * nothing we can read says "this was reset". Our record would then honestly describe data that is gone,
     * and we would skip it forever.
     *
     * <p>So: set this true, join once, set it back. {@code /cslod set reinject-on-join true} does that
     * without leaving the game. Deleting the {@code .injected} files in the store does exactly the same
     * thing for anyone who would rather do it that way.
     */
    public static final String KEY_REINJECT = "reinject-on-join";

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
     * being handed the config directory again.
     *
     * <p>Null until load() has run -- on a dedicated server this class is never touched, and in a unit test
     * there is no game directory. A save with no path applies the value in memory and writes nothing, which
     * is the honest behaviour: there is no file to keep in step.
     */
    private static volatile Path file;

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

        // Anything that is not literally "true" is false. A misspelt value must not silently turn a
        // one-shot recovery switch into the permanent behaviour it exists to work around.
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

    /** True when the player has asked for one session of full re-injection. See {@link #KEY_REINJECT}. */
    public static boolean reinjectOnJoin() {
        return reinject;
    }

    /** Has {@link #load} run? Only used to keep the tick loop from polling before we know the interval. */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Set the sync interval and save it. Clamped ON WRITE as well as on read, so the file can never hold a
     * number the client would refuse to honour -- a person who reads the file back should see what is
     * actually in force, not what they asked for.
     *
     * @param seconds the requested interval
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

    /** Test seam: set the re-inject switch directly, as though it had been read from a file. */
    static void setReinjectForTesting(final boolean value) {
        reinject = value;
    }

    /**
     * Write the values CURRENTLY in force -- not the defaults.
     *
     * <p>Called on first run (where the two are the same thing) and after every command write. A failure is
     * swallowed for the same reason it always was: the value is already in effect in memory, and a player
     * who cannot write to their own config directory has a bigger problem than our sync interval.
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
