package com.kishku7.chunksmith.lod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
//[[[cog
// import cog, compat
// if compat.has_lod_renderer_integration(mcver, loader):
//     cog.outl("import net.fabricmc.loader.api.FabricLoader;")
//]]]
//[[[end]]]

/**
 * Second Fabric entrypoint, owning everything LOD.
 *
 * <p>Deliberately separate from {@code ChunksmithFabric}: that class is the mod's general entrypoint,
 * and LOD is an OPT-IN feature that only some cells carry. Keeping it here means the LOD wiring never
 * drifts into the general entrypoint, and a cell without LOD simply does not list this entrypoint in its
 * {@code fabric.mod.json}.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class LodInit implements ModInitializer {

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CsLodCommand.build()));

        // The LOD protocol: the channel is registered at init; the HTTP backchannel binds
        // once the server is up and its port is known, and unbinds when it stops.
        com.kishku7.chunksmith.lod.net.CsLodServerNet.register();
        ServerLifecycleEvents.SERVER_STARTED.register(
                com.kishku7.chunksmith.lod.net.CsLodServerNet::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            com.kishku7.chunksmith.lod.net.CsLodServerNet.onServerStopped();
            // Flush the writer queue and close the region files -- otherwise a pregen that ends at
            // shutdown would lose whatever was still queued.
            LodSupport.shutdown();
        });
        // Drip-feed the in-band fallback. A few slices per tick, never a burst.
        ServerTickEvents.END_SERVER_TICK.register(com.kishku7.chunksmith.lod.net.CsLodServerNet::tick);

        //[[[cog
        // import cog, compat
        // if compat.has_lod_renderer_integration(mcver, loader):
        //     cog.outl("// CsLodDhSupport hard-references Distant Horizons types, so it must not be class-loaded")
        //     cog.outl("// unless DH is actually installed.")
        //     cog.outl("//")
        //     cog.outl("// Both calls have to happen this early. DH fires its level-load event from Fabric's")
        //     cog.outl("// ServerWorldEvents.LOAD -- i.e. while the server is still STARTING -- so a binding made on")
        //     cog.outl("// SERVER_STARTED is already too late to override its generator, and SERVER_STARTING is the")
        //     cog.outl("// last point at which the MinecraftServer can be captured before that event fires.")
        //     cog.outl('if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {')
        //     cog.outl("    try {")
        //     cog.outl("        ServerLifecycleEvents.SERVER_STARTING.register(CsLodDhSupport::setServer);")
        //     cog.outl("        CsLodDhSupport.register();")
        //     cog.outl("    } catch (final LinkageError error) {")
        //     cog.outl('        System.out.println("[chunksmith] Distant Horizons present but incompatible, skipping: " + error);')
        //     cog.outl("    }")
        //     cog.outl("}")
        // else:
        //     cog.outl("// No direct Distant Horizons hook on this cell: the DH generator-override + push classes")
        //     cog.outl("// compile against the DH API jar and are a SINGLEPLAYER path. A dedicated server serves the")
        //     cog.outl("// CSLOD store over the backchannel and lets Chunksmith-Client feed the player's own DH.")
        //]]]
        //[[[end]]]
    }
}
