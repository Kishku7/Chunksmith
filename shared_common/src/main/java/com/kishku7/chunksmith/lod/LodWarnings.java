package com.kishku7.chunksmith.lod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says out loud, ONCE, when a renderer we detected turns out not to work the way we expected.
 * <p>That happened for real: a fork that declares {@code int sectionRenderDistance} where upstream voxy
 * declares {@code float} produced a {@code NoSuchFieldError}, which was swallowed, which silently
 * collapsed the LOD radius from 8192 blocks to the 256-block protocol default -- a 32x collapse, reported
 * as success. Never again: a renderer that fails to accept our data, or whose settings we cannot read, is
 * a thing the player must be TOLD about, in words, naming what broke and what we did instead.
 */
public final class LodWarnings {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private LodWarnings() {
    }

    public static void once(final String cause, final String message) {
        if (SAID.add(cause)) {
            LOGGER.warn("Chunksmith: {}", message);
        }
    }

    public static boolean saidAlready(final String cause) {
        return SAID.contains(cause);
    }

    public static void reset() {
        SAID.clear();
    }
}
