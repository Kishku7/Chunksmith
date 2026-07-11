package com.kishku7.chunksmith.lod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Second Fabric entrypoint, owning everything LOD.
 *
 * <p>Deliberately separate from {@code ChunksmithFabric}: that class is COG-GENERATED from
 * {@code _codegen/cog_sources}, and the LOD feature is Fabric-only, so it has no business drifting
 * into the shared generated entrypoint. Everything stays inside {@code com.kishku7.chunksmith.lod}
 * and out of the codegen surface.
 */
public final class LodInit implements ModInitializer {

    @Override
    public void onInitialize() {
        CsLodCommand.register();

        // The LOD protocol: the channel is registered at init; the HTTP backchannel binds
        // once the server is up and its port is known, and unbinds when it stops.
        com.kishku7.chunksmith.lod.net.CsLodServerNet.register();
        ServerLifecycleEvents.SERVER_STARTED.register(
                com.kishku7.chunksmith.lod.net.CsLodServerNet::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> com.kishku7.chunksmith.lod.net.CsLodServerNet.onServerStopped());

        // CsLodDhSupport hard-references Distant Horizons types, so it must not be class-loaded
        // unless DH is actually installed.
        //
        // Both calls have to happen this early. DH fires its level-load event from Fabric's
        // ServerWorldEvents.LOAD -- i.e. while the server is still STARTING -- so a binding made on
        // SERVER_STARTED is already too late to override its generator, and SERVER_STARTING is the
        // last point at which the MinecraftServer can be captured before that event fires.
        if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
            try {
                ServerLifecycleEvents.SERVER_STARTING.register(CsLodDhSupport::setServer);
                CsLodDhSupport.register();
            } catch (final LinkageError error) {
                System.out.println("[chunksmith] Distant Horizons present but incompatible, skipping: " + error);
            }
        }
    }
}
