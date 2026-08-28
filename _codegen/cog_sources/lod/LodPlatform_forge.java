package com.kishku7.chunksmith.lod;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * The classic-Forge copy of the loader seam (MC 1.20.1 / Forge 47). See the Fabric copy for what this
 * exists for.
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
