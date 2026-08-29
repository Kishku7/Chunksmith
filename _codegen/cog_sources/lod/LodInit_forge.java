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

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.kishku7.chunksmith.lod.net.CsLodServerNet;
import net.minecraftforge.fml.ModList;
import org.slf4j.LoggerFactory;

/**
 * The classic-Forge LOD entrypoint (MC 1.20.1 / Forge 47): everything LOD, and nothing else.
 *
 * <p>A GAME-bus {@code @Mod.EventBusSubscriber} rather than a hook inside {@code
 * ChunksmithForge}: FML registers every subscriber automatically, so a cell without
 * the LOD feature simply does not ship this class and the general entrypoint never
 * learns that LOD exists. The channel is built by {@code CsLodChannel}'s static
 * initializer (a MOD-bus subscriber), because Forge's network registry only accepts a
 * new channel during mod construction.
 *
 * <p>{@code TickEvent.ServerTickEvent} did not carry a {@link MinecraftServer} on
 * every Forge 47 build, so the server is captured on start rather than read off the
 * tick event.
 */
@Mod.EventBusSubscriber(modid = "chunksmith")
public final class LodInit {

    private static volatile MinecraftServer server;

    private LodInit() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(CsLodCommand.build());
    }

    /**
     * Binds Distant Horizons at the last point before it reports its levels. {@code
     * ServerAboutToStartEvent} fires BEFORE {@code initServer()}, so before {@code
     * createLevels()} and therefore before DH's level-load event. {@code
     * ServerStartedEvent} would already be too late to override its generator.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        //[[[cog
        // import cog, compat
        // if compat.has_dh(mcver, loader):
        //     cog.outl("// CsLodDhSupport hard-references Distant Horizons types, so it must not be class-loaded")
        //     cog.outl("// unless DH is actually installed. In SINGLEPLAYER the integrated server is in the client")
        //     cog.outl("// JVM, so this is the whole LOD path: no Chunksmith-Client and no network -- we hand the")
        //     cog.outl("// player's own DH its data directly. (DH ships a FORGE build on 1.20.1, not a NeoForge one.)")
        //     cog.outl('if (LodPlatform.isModLoaded("distanthorizons")) {')
        //     cog.outl("    try {")
        //     cog.outl("        CsLodDhSupport.setServer(event.getServer());")
        //     cog.outl("        CsLodDhSupport.register();")
        //     cog.outl("    } catch (final LinkageError error) {")
        //     cog.outl('        org.slf4j.LoggerFactory.getLogger("Chunksmith").warn(')
        //     cog.outl('                "Chunksmith: Distant Horizons present but incompatible, skipping: {}", error.toString());')
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
        warnOnConflicts();
        server = event.getServer();
        LodSupport.announce(event.getServer());
        // Make the CSLOD store visible to the pregen's skip decision, so a re-run fills LOD holes
        // instead of skipping every already-generated chunk (and so never building their LODs).
        LodSupport.install(event.getServer());
        CsLodServerNet.onServerStarted(event.getServer());
    }

    /**
     * The LOD-streamer conflict check, done at runtime on this cell only: Forge 47's {@code mods.toml} has
     * no incompatible dependency type (only {@code mandatory = true|false}), so the incompatibility every
     * other cell declares in its manifest (Fabric {@code breaks}, NeoForge {@code type
     * = "incompatible"}) is not expressible here and has to be surfaced in the log
     * instead.
     */
    private static void warnOnConflicts() {
        for (String other : new String[] {"lss", "voxyserver", "lodserver"}) {
            if (ModList.get().isLoaded(other)) {
                LoggerFactory.getLogger("Chunksmith").error(
                        "The mod '" + other + "' also streams LOD data to clients. Running it alongside "
                                + "Chunksmith's LOD feature means two uncoordinated writers into one LOD "
                                + "database: duplicated downloads and corrupted renderer state. Remove one.");
            }
        }
        // The standalone Chunksmith-Client, DISCONTINUED at 3.1.0; its multiplayer LOD half IS this jar
        // now. Both register the chunksmith:lod channel, so having both is a duplicate registration.
        if (ModList.get().isLoaded("chunksmithclient")) {
            LoggerFactory.getLogger("Chunksmith").error(
                    "Chunksmith-Client is installed alongside Chunksmith 3.1.0+. It is DISCONTINUED: its "
                            + "multiplayer LOD feature is now built into Chunksmith itself, and running both "
                            + "means two mods registering the same 'chunksmith:lod' channel. Remove the "
                            + "Chunksmith-Client jar; you lose nothing.");
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        server = null;
        CsLodServerNet.onServerStopped();
        // Flush the writer queue and close the region files, or a pregen that ends at shutdown loses
        // whatever was still queued.
        LodSupport.shutdown();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer current = server;
        if (current != null) {
            CsLodServerNet.tick(current);
        }
    }
}
