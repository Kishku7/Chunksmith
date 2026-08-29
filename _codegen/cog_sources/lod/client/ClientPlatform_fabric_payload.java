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
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Before the 3.1.0 merge the LOD client was a separate mod, and both mods
 * registered {@code chunksmith:lod} in {@code PayloadTypeRegistry}, so a player
 * who had both (a self-hoster who plays singleplayer and joins a friend's
 * Chunksmith server) got {@code IllegalArgumentException: Packet type ...
 * [id=chunksmith:lod] is already registered!} and a hard crash on startup. One mod
 * now, and the type is registered exactly once, by {@link
 * CsLodChannel#register()}, from the common mod init that runs on both sides. This
 * class therefore does not register the payload type; it cannot. What happens here
 * is a receiver registration against that already-registered type: a different
 * registry, and one that only exists on a client.
 *
 * <p>It is also the Fabric payload-era (MC 1.20.5+) half of a seam class: same
 * package, same name, same static signatures on every loader and every MC version,
 * so the shared LOD-client tree calls {@code ClientPlatform.x()} and names no
 * loader type. The facade is the only place a loader symbol appears.
 */
@Environment(EnvType.CLIENT)
public final class ClientPlatform {

    private ClientPlatform() {
    }

    /** Fabric hands the client entrypoint nothing. Kept so the entrypoints have one shape across loaders. */
    public static void bootstrap(Object bus) {
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    /**
     * Runs an action once the client is far enough up to talk to other mods' APIs.
     *
     * <p>On Fabric that is the client-init entrypoint itself: client initializers
     * run after the mod list is built, and Distant Horizons' own initializer has
     * run by the time it fires its level-load event. So this is an immediate call.
     * NeoForge and Forge defer it to {@code FMLClientSetupEvent}, where mod
     * construction can run before DH's own and {@code DhApi.events} would not be
     * there yet.
     */
    public static void onClientSetup(Runnable action) {
        action.run();
    }

    /**
     * Hands every server payload to {@code onPayload}, on the client thread.
     *
     * <p>Receiver only -- see the class doc. {@code CsLodChannel.Payload.TYPE} is
     * the same type object the common init registered; asking for it here neither
     * creates nor re-registers anything.
     */
    public static void registerClientNetworking(Consumer<byte[]> onPayload) {
        ClientPlayNetworking.registerGlobalReceiver(CsLodChannel.Payload.TYPE, (payload, context) ->
                context.client().execute(() -> onPayload.accept(payload.data())));
    }

    /**
     * Sends raw protocol bytes to the connected server. Silently does nothing on
     * the many servers that do not speak our channel.
     */
    public static void sendToServer(byte[] data) {
        if (ClientPlayNetworking.canSend(CsLodChannel.Payload.TYPE)) {
            ClientPlayNetworking.send(new CsLodChannel.Payload(data));
        }
    }

    public static void onJoin(Runnable action) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> action.run());
    }

    public static void onDisconnect(Runnable action) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> action.run());
    }

    public static void onClientTick(Runnable action) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> action.run());
    }
}
