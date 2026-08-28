package com.kishku7.chunksmith.lod;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge 47 / MC 1.20.1 sits behind this seam. See the Fabric copy for what the seam exists for.
 */
public final class LodPlatform {

    private LodPlatform() {
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
