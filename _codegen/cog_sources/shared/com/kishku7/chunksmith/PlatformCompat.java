package com.kishku7.chunksmith;

/**
 * Loader-agnostic holder for platform/compat flags. Each loader entrypoint sets these at
 * init; shared Minecraft-touching code (mixins, platform wrappers) reads them. Keeps the
 * shared tree free of any reference to a specific loader's entrypoint class.
 */
public final class PlatformCompat {
    /** True when the Moonrise mod is present. Set by the loader entrypoint at init. */
    public static volatile boolean ENABLE_MOONRISE_WORKAROUNDS = false;

    private PlatformCompat() {
    }
}