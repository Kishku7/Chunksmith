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

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.kishku7.chunksmith.lod.net.CsLodServerNet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The NeoForge LOD entrypoint: everything LOD, and nothing else.
 *
 * <p>A GAME-bus {@code @EventBusSubscriber} rather than a hook inside {@code ChunksmithNeoForge}:
 * FML registers every subscriber automatically, so a cell without the LOD feature simply does not
 * ship this class and the general entrypoint never learns that LOD exists. The payload
 * registration is a MOD-bus event and lives in {@code CsLodChannel}.
 *
 * <p>Shared source: canonical location _codegen/cog_sources/lod; the gen/ copy is overwritten each build.
 */
@EventBusSubscriber(modid = "chunksmith")
public final class LodInit {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private LodInit() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(CsLodCommand.build());
    }

    /**
     * Binds Distant Horizons at the last point before it reports its levels. {@code
     * ServerAboutToStartEvent} fires from {@code MinecraftServer.runServer} BEFORE {@code
     * initServer()}, so before {@code createLevels()} and therefore before DH's level-load event.
     * {@code ServerStartedEvent} would already be too late to override its generator.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        //[[[cog
        // import cog, compat
        // if compat.has_dh(mcver, loader):
        //     cog.outl("// CsLodDhSupport hard-references Distant Horizons types, so it must not be class-loaded")
        //     cog.outl("// unless DH is actually installed. In SINGLEPLAYER the integrated server is in the client")
        //     cog.outl("// JVM, so this is the whole LOD path: no Chunksmith-Client and no network -- we hand the")
        //     cog.outl("// player's own DH its data directly.")
        //     cog.outl('if (LodPlatform.isModLoaded("distanthorizons")) {')
        //     cog.outl("    try {")
        //     cog.outl("        CsLodDhSupport.setServer(event.getServer());")
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

    /** Binds the HTTP backchannel once the server is up and its port is known. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LodSupport.announce(event.getServer());
        // Make the CSLOD store visible to the pregen's skip decision, so a re-run fills LOD holes
        // instead of skipping every already-generated chunk (and so never building their LODs).
        LodSupport.install(event.getServer());
        CsLodServerNet.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CsLodServerNet.onServerStopped();
        // Flush the writer queue and close the region files, or a pregen that ends at shutdown loses
        // whatever was still queued.
        LodSupport.shutdown();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        CsLodServerNet.tick(event.getServer());
    }

    /** A token must never outlive the session that earned it. */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CsLodServerNet.onDisconnect(event.getEntity().getUUID());
    }
}
