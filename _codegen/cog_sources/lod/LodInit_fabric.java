/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.lod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.kishku7.chunksmith.lod.net.CsLodServerNet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires up everything LOD on Fabric, from a second entrypoint of its own.
 *
 * <p>Deliberately separate from {@code ChunksmithFabric}: that class is the mod's general entrypoint,
 * and LOD is an OPT-IN feature that only some cells carry. Keeping it here means the LOD wiring never
 * drifts into the general entrypoint, and a cell without LOD simply does not list this entrypoint in its
 * {@code fabric.mod.json}.
 */
public final class LodInit implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CsLodCommand.build()));

        // The LOD protocol: the channel is registered at init; the HTTP backchannel binds
        // once the server is up and its port is known, and unbinds when it stops.
        CsLodServerNet.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Say what the lodEnabled tristate resolved to, and why, BEFORE anything acts on it.
            LodSupport.announce(server);
            // Make the CSLOD store visible to the pregen's skip decision, so a re-run fills LOD holes
            // instead of skipping every already-generated chunk (and so never building their LODs).
            LodSupport.install(server);
            CsLodServerNet.onServerStarted(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CsLodServerNet.onServerStopped();
            // Flush the writer queue and close the region files; otherwise a pregen that ends at
            // shutdown would lose whatever was still queued.
            LodSupport.shutdown();
        });
        // Drip-feed the in-band fallback. A few slices per tick, never a burst.
        ServerTickEvents.END_SERVER_TICK.register(CsLodServerNet::tick);

        //[[[cog
        // import cog, compat
        // if compat.has_dh(mcver, loader):
        //     cog.outl("// CsLodDhSupport hard-references Distant Horizons types, so it must not be class-loaded")
        //     cog.outl("// unless DH is actually installed.")
        //     cog.outl("//")
        //     cog.outl("// Both calls have to happen this early. DH fires its level-load event from Fabric's")
        //     cog.outl("// ServerWorldEvents.LOAD -- i.e. while the server is still STARTING -- so a binding made on")
        //     cog.outl("// SERVER_STARTED is already too late to override its generator, and SERVER_STARTING is the")
        //     cog.outl("// last point at which the MinecraftServer can be captured before that event fires.")
        //     cog.outl("//")
        //     cog.outl("// In SINGLEPLAYER the integrated server is in the client JVM, so this is the whole LOD path:")
        //     cog.outl("// no network hop -- we hand the player's own DH its data directly.")
        //     cog.outl('if (LodPlatform.isModLoaded("distanthorizons")) {')
        //     cog.outl("    try {")
        //     cog.outl("        ServerLifecycleEvents.SERVER_STARTING.register(CsLodDhSupport::setServer);")
        //     cog.outl("        CsLodDhSupport.register();")
        //     cog.outl("    } catch (final LinkageError error) {")
        //     cog.outl('        LOGGER.warn("Chunksmith: Distant Horizons present but incompatible, skipping: {}", error.toString());')
        //     cog.outl("    }")
        //     cog.outl("}")
        // else:
        //     cog.outl("// No LOD renderer exists for this (loader, MC) at all, so there is nothing to bind.")
        //]]]
        //[[[end]]]
    }
}
