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

package com.kishku7.chunksmith.lod.net;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Carries the in-band LOD channel on Fabric before MC 1.20.2, where the modern
 * payload API does not exist yet.
 *
 * <p>{@code CustomPacketPayload} is absent here, so there is no payload object and no
 * StreamCodec: the channel is a plain {@code (ResourceLocation, FriendlyByteBuf)}
 * pair. The wire is nevertheless byte-identical to the modern cells (a
 * length-prefixed byte array on channel {@code chunksmith:lod}), because {@code
 * writeByteArray} is the same varint+bytes encoding the modern StreamCodec emits.
 *
 * <p>{@code ResourceLocation(String,String)} is still public here (privatized at 1.21
 * in favour of {@code fromNamespaceAndPath}), so the ctor form is correct and this
 * file needs no Cog.
 */
public final class CsLodChannel {

    public static final ResourceLocation ID =
            new ResourceLocation(CsLodProtocol.NAMESPACE, CsLodProtocol.CHANNEL);

    private CsLodChannel() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, responseSender) -> {
            // Read on the NETTY thread. The buffer is released the instant this handler returns, so the
            // bytes MUST be copied out before hopping to the main thread. Reading it inside the
            // server.execute lambda would race the release and hand us garbage (or throw).
            byte[] data = buf.readByteArray();
            server.execute(() -> CsLodServerNet.receive(player, data));
        });

        // A token must never outlive the session that earned it.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, ignored) ->
                CsLodServerNet.onDisconnect(handler.getPlayer().getUUID()));
    }

    public static void send(ServerPlayer player, byte[] data) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeByteArray(data);
        ServerPlayNetworking.send(player, ID, buf);
    }
}
