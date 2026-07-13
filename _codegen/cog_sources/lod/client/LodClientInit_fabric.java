package com.kishku7.chunksmith.lod.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * The LOD CLIENT entrypoint -- FABRIC.
 *
 * <p><b>This class is the side guard.</b> It is listed in {@code fabric.mod.json} under {@code "client"},
 * never {@code "main"}. Fabric Loader does not invoke client entrypoints on a dedicated server -- it does
 * not construct the class at all -- so nothing below this line, and nothing it reaches (the download client,
 * the renderer adapters, {@code ClientPlatform}, anything touching {@code net.minecraft.client.*}), can be
 * loaded on a headless box. That is what makes carrying client code inside a server mod safe.
 *
 * <p>Chunksmith's SERVER-side LOD entrypoint is {@code lod.LodInit} (a {@code "main"} entrypoint) and it
 * runs on both sides, as it must -- it owns the ONE registration of the {@code chunksmith:lod} payload type.
 * This class only ever attaches a RECEIVER to that already-registered type.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod/client. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class LodClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CsLodClientBoot.init();
    }
}
