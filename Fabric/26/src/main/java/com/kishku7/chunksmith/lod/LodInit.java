package com.kishku7.chunksmith.lod;

import net.fabricmc.api.ModInitializer;

/**
 * Second Fabric entrypoint, owning everything LOD.
 *
 * <p>Deliberately separate from {@code ChunksmithFabric}: that class is COG-GENERATED from
 * {@code _codegen/cog_sources}, and the LOD feature is Fabric-only and voxy-only, so it has no
 * business drifting into the shared generated entrypoint. Registering our own initializer keeps the
 * whole feature inside {@code com.kishku7.chunksmith.lod} and out of the codegen surface.
 */
public final class LodInit implements ModInitializer {

    @Override
    public void onInitialize() {
        CsLodCommand.register();
    }
}
