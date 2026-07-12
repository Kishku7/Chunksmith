package com.kishku7.chunksmith.lod;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * The NeoForge copy of the loader seam. See the Fabric copy for what this exists for.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
 */
public final class LodPlatform {

    private LodPlatform() {
    }

    /** Is a mod with this id loaded? The gate that keeps a hard-referencing adapter class off the heap. */
    public static boolean isModLoaded(final String modId) {
        return ModList.get().isLoaded(modId);
    }

    /** The instance config directory -- the same one ChunksmithNeoForge builds its config from. */
    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
