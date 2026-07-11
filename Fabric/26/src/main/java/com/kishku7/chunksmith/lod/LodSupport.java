package com.kishku7.chunksmith.lod;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Resolves the active {@link LodSink} for this runtime.
 *
 * <p>Soft dependency gate. {@link VoxyLodSink} hard-references voxy classes, so it is only ever
 * loaded once {@code isModLoaded("voxy")} has returned true -- the class is not resolved until
 * {@code new VoxyLodSink()} actually executes. If voxy is absent, or its API has moved under us,
 * we fall back to {@link LodSink#NOOP} and pregen proceeds untouched.
 *
 * <p>This follows the existing soft-dep precedent in this repo (PlatformCompat's Moonrise gate).
 */
public final class LodSupport {

    private static volatile LodSink sink;

    private LodSupport() {
    }

    /** The active sink. Resolved once, lazily, on first use. Never null. */
    public static LodSink sink() {
        LodSink local = sink;
        if (local == null) {
            synchronized (LodSupport.class) {
                local = sink;
                if (local == null) {
                    local = create();
                    sink = local;
                }
            }
        }
        return local;
    }

    private static LodSink create() {
        if (!FabricLoader.getInstance().isModLoaded("voxy")) {
            return LodSink.NOOP;
        }
        try {
            final LodSink voxy = new VoxyLodSink();
            System.out.println("[chunksmith] voxy detected -- LOD generation enabled (" + voxy + ")");
            return voxy;
        } catch (final LinkageError error) {
            // voxy present but its API moved: degrade loudly rather than crashing the pregen.
            System.out.println("[chunksmith] voxy present but incompatible, LOD generation disabled: " + error);
            return LodSink.NOOP;
        }
    }
}
