package com.kishku7.chunksmith.lod;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * The NeoForge copy of the loader seam. See the Fabric copy for what this exists for.
 */
public final class LodPlatform {

    private LodPlatform() {
    }

    public static boolean isModLoaded(final String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
