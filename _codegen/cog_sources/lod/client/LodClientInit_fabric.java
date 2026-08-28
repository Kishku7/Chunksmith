package com.kishku7.chunksmith.lod.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * The LOD CLIENT entrypoint -- FABRIC.
 *
 * <p>Chunksmith's SERVER-side LOD entrypoint is {@code lod.LodInit} (a {@code "main"} entrypoint) and it
 * runs on both sides, as it must -- it owns the ONE registration of the {@code chunksmith:lod} payload type.
 * This class only ever attaches a RECEIVER to that already-registered type.
 */
public final class LodClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CsLodClientBoot.init();
    }
}
