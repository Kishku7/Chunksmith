package com.kishku7.chunksmith.lod;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * One copy per loader; cog-gen picks the right one. This is the FABRIC copy.
 */
public final class LodPlatform {

    private LodPlatform() {
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
