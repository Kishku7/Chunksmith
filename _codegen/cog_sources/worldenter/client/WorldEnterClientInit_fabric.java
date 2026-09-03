package com.kishku7.chunksmith.worldenter.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Fabric's client-side half of the world-enter pregen.
 *
 * <p>The side guard is the LOADER's, not a runtime {@code if}: this class is declared in the
 * {@code "client"} entrypoint slot of {@code fabric.mod.json}, so Fabric Loader never constructs it
 * -- and never class-loads the screen behind it -- on a dedicated server. Same mechanism the LOD
 * client half uses. A runtime check would not be equivalent, because the class would still be
 * loaded, and loading a Screen subclass on a server is exactly the crash this avoids.
 */
public final class WorldEnterClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(WorldEnterClientHook::tick);
    }
}
