package com.kishku7.chunksmith;

/**
 * Loader-agnostic holder for platform/compat flags. Each loader entrypoint sets these at
 * init; shared Minecraft-touching code (mixins, platform wrappers) reads them. Keeps the
 * shared tree free of any reference to a specific loader's entrypoint class.
 */
public final class PlatformCompat {
    /** True when the Moonrise mod is present. Set by the loader entrypoint at init. */
    public static volatile boolean ENABLE_MOONRISE_WORKAROUNDS = false;

    /**
     * True when C2ME (Concurrent Chunk Management Engine) is present. Set by the loader
     * entrypoint at init. C2ME rewrites the chunk ticket/distance manager to be processed by
     * its own concurrent scheduler; forcing an out-of-cadence synchronous distance-manager
     * update per ticket (as vanilla-targeted code does) re-enters that rewritten code far more
     * often than vanilla's own once-per-tick cadence, which has been observed to trigger a
     * race in C2ME's ticket map (crash: NPE in Long2ByteOpenHashMap iteration, "this.wrapped"
     * null, while ticking chunk tickets). Currently only consulted by the Fabric 1.21.11
     * FabricWorld variant (compat_platform.py "a2e3c49d5235"); harmless/no-op elsewhere.
     */
    public static volatile boolean ENABLE_C2ME_TICKET_COMPAT = false;

    private PlatformCompat() {
    }
}
