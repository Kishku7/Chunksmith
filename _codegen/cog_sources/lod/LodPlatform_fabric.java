package com.kishku7.chunksmith.lod;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * The ONE loader-specific thing the renderer adapters need: "is that mod installed, and where is the
 * config directory".
 *
 * <p>Everything else about the adapters is loader-blind -- Distant Horizons' API names no Minecraft type
 * and no loader type, so {@code CsLodDhSupport}, {@code CsLodDhGenerator} and {@code CsLodDhPusher} are
 * single-sourced across all three loaders and only reach the loader through this seam. Without it, the DH
 * adapter would have to be copied per loader for two method calls.
 *
 * <p>One copy per loader; cog-gen picks the right one. This is the FABRIC copy.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
 */
public final class LodPlatform {

    private LodPlatform() {
    }

    /** Is a mod with this id loaded? The gate that keeps a hard-referencing adapter class off the heap. */
    public static boolean isModLoaded(final String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    /** The instance config directory -- {@code CsLodDhSupport} reads the config off disk from here. */
    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
