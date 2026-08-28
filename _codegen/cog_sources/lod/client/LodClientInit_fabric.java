package com.kishku7.chunksmith.lod.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Attaches the client-side LOD receiver on Fabric.
 *
 * <p>Chunksmith's server-side LOD entrypoint is {@code lod.LodInit} (a {@code "main"} entrypoint) and it
 * runs on both sides, as it must -- it owns the one registration of the {@code chunksmith:lod} payload type.
 * This class only ever attaches a receiver to that already-registered type.
 */
public final class LodClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CsLodClientBoot.init();
    }
}
