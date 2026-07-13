package com.kishku7.chunksmith.lod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says out loud, ONCE, when a renderer we detected turns out not to work the way we expected.
 *
 * <p><b>Why this class exists.</b> Chunksmith talks to two third-party renderers -- Distant Horizons and
 * voxy -- and voxy in particular is forked constantly. A fork can rename a field, re-type it, or move a
 * method, and the JVM reports that as a {@link LinkageError} ({@code NoSuchFieldError},
 * {@code NoSuchMethodError}, {@code NoClassDefFoundError}). Every one of those used to be caught and
 * thrown away. The player then got LESS terrain, or none, and NOTHING in the log said why.
 *
 * <p>That happened for real: a fork that declares {@code int sectionRenderDistance} where upstream voxy
 * declares {@code float} produced a {@code NoSuchFieldError}, which was swallowed, which silently
 * collapsed the LOD radius from 8192 blocks to the 256-block protocol default -- a 32x collapse, reported
 * as success. Never again: a renderer that fails to accept our data, or whose settings we cannot read, is
 * a thing the player must be TOLD about, in words, naming what broke and what we did instead.
 *
 * <p><b>Once.</b> These failures repeat per chunk, per section, per region. A warning per occurrence would
 * bury the log and the game. One warning per distinct CAUSE, per session, is the contract -- loud enough to
 * be seen, quiet enough to be read.
 */
public final class LodWarnings {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** Causes already announced this session. */
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private LodWarnings() {
    }

    /**
     * Warn about {@code cause} the first time it happens, and stay quiet about it afterwards.
     *
     * @param cause   a stable key for the failure (not shown to the player) -- one warning per key
     * @param message the plain-words explanation, shown to the player. Say what failed AND what we did
     *                instead; a warning that does not tell the player what changed is not much better
     *                than silence.
     */
    public static void once(final String cause, final String message) {
        if (SAID.add(cause)) {
            LOGGER.warn("Chunksmith: {}", message);
        }
    }

    /** Whether {@code cause} has already been announced. Exposed for tests and for callers that want to
     * do expensive message-building only when it will actually be printed. */
    public static boolean saidAlready(final String cause) {
        return SAID.contains(cause);
    }

    /** Forget everything said. For tests, and for a session boundary. */
    public static void reset() {
        SAID.clear();
    }
}
