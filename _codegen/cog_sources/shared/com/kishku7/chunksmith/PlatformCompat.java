package com.kishku7.chunksmith;

/**
 * Loader-agnostic holder for platform/compat flags, set by each loader entrypoint at init.
 */
public final class PlatformCompat {
    public static volatile boolean ENABLE_MOONRISE_WORKAROUNDS = false;

    /**
     * True when C2ME is present. C2ME rewrites the ticket/distance manager onto its own concurrent
     * scheduler; the out-of-cadence synchronous distance-manager update per ticket that
     * vanilla-targeted code does re-enters it far more often than vanilla's once-per-tick cadence,
     * and was observed to race its ticket map (NPE in Long2ByteOpenHashMap iteration, "this.wrapped"
     * null, while ticking chunk tickets). Only consulted by the Fabric 1.21.11 FabricWorld variant
     * (compat_platform.py "a2e3c49d5235"); no-op elsewhere.
     */
    public static volatile boolean ENABLE_C2ME_TICKET_COMPAT = false;

    private PlatformCompat() {
    }
}
