package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.ChunksmithProvider;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Resolves the active {@link LodSink} for this runtime and publishes it to {@link LodSinks}.
 *
 * <p>Two gates, both of which must pass:
 * <ol>
 *   <li><b>Opt-in.</b> {@code lodEnabled} in the config, default FALSE. LOD ingestion measurably
 *       slows a pregen (~2.4x on the 26.1.2 spike), so it is never switched on behind the
 *       operator's back.</li>
 *   <li><b>Soft dependency.</b> {@code isModLoaded("voxy")}. {@link VoxyLodSink} hard-references
 *       voxy types, so it is only class-loaded once voxy is known present; a {@link LinkageError}
 *       (voxy present but its API moved) degrades to NOOP rather than killing the pregen.</li>
 * </ol>
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
                    LodSinks.set(local);
                }
            }
        }
        return local;
    }

    private static LodSink create() {
        if (!lodEnabledInConfig()) {
            return LodSink.NOOP;
        }
        if (!FabricLoader.getInstance().isModLoaded("voxy")) {
            System.out.println("[chunksmith] LOD generation is enabled but voxy is not installed -- nothing to feed.");
            return LodSink.NOOP;
        }
        try {
            final LodSink voxy = new VoxyLodSink();
            System.out.println("[chunksmith] voxy detected -- LOD generation enabled (" + voxy + ")");
            return voxy;
        } catch (final LinkageError error) {
            System.out.println("[chunksmith] voxy present but incompatible, LOD generation disabled: " + error);
            return LodSink.NOOP;
        }
    }

    private static boolean lodEnabledInConfig() {
        // ChunksmithProvider.get() THROWS when unloaded, so gate on isLoaded() first.
        return ChunksmithProvider.isLoaded() && ChunksmithProvider.get().getConfig().isLodEnabled();
    }
}
