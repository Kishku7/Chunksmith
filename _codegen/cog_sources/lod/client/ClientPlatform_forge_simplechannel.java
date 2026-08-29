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

package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The CLIENT-side platform facade for classic FORGE (MC 1.20.1 / Forge 47), the SimpleChannel era.
 *
 * <p><b>The one registration.</b> Forge 47 predates {@code
 * CustomPacketPayload}: the transport is a versioned {@code
 * SimpleChannel} built through {@code NetworkRegistry}, and it must be
 * built while the network registry is still open (mod construction).
 * {@link CsLodChannel} owns that: one channel, one {@code
 * messageBuilder}, built by its static initializer on both sides. This
 * class registers nothing; it installs the client sink that {@code
 * CsLodChannel.Message.handle} drains into when the message arrived from
 * a server ({@code context.getSender() == null}). On a dedicated server
 * the sink is never set and that branch is dead.
 *
 * <p>Forge's SimpleChannel prefixes every message with a discriminator
 * byte that a raw Fabric channel does not, so a Forge client is
 * wire-compatible with a Forge server and a Fabric client with a Fabric
 * server. Chunksmith ships both loaders on 1.20.1, so both pairings
 * exist; a Forge client on a Fabric 1.20.1 server never completes the
 * SimpleChannel handshake, {@link #sendToServer} sees no remote channel,
 * and the LOD client stays quiet (exactly what it does on any server
 * that is not running Chunksmith).
 *
 * <p>Shared source; canonical location: _codegen/cog_sources/lod/client.
 * Edit only there; the per-cell copy under gen/ is overwritten by
 * cog-gen on every build.
 */
public final class ClientPlatform {

    private ClientPlatform() {
    }

    /** Forge hands the client bootstrap nothing it needs. Kept so the entrypoints have one shape. */
    public static void bootstrap(Object bus) {
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /**
     * Runs an action once the client is far enough up to talk to other mods' APIs.
     *
     * <p>On this loader we are ALREADY there: {@code LodClientInit} is a
     * {@code Dist.CLIENT} MOD-bus subscriber whose only handler is
     * {@code FMLClientSetupEvent}, and it is what called into here. So
     * this is an immediate call, and the loader -- not a runtime check
     * -- is what kept us off the server.
     */
    public static void onClientSetup(Runnable action) {
        action.run();
    }

    /** Installs the client sink. The channel itself was built once, by {@code CsLodChannel}. */
    public static void registerClientNetworking(Consumer<byte[]> onPayload) {
        CsLodChannel.setClientSink(onPayload);
    }

    /**
     * Sends raw protocol bytes to the connected server. Silently does
     * nothing on the many servers that do not speak our channel.
     */
    public static void sendToServer(byte[] data) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null || !CsLodChannel.isRemotePresent(listener.getConnection())) {
            return;
        }
        CsLodChannel.sendToServer(data);
    }

    public static void onJoin(Runnable action) {
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> action.run());
    }

    public static void onDisconnect(Runnable action) {
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> action.run());
    }

    public static void onClientTick(Runnable action) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                action.run();
            }
        });
    }
}
