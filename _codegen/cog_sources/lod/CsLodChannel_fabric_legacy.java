package com.kishku7.chunksmith.lod.net;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Carries the in-band LOD channel on Fabric before MC 1.20.2, where the modern payload API does not
 * exist yet.
 *
 * <p>{@code CustomPacketPayload} is absent here, so there is no payload object and no StreamCodec: the
 * channel is a plain {@code (ResourceLocation, FriendlyByteBuf)} pair. The wire is nevertheless
 * byte-identical to the modern cells -- a length-prefixed byte array on channel {@code chunksmith:lod} --
 * because {@code writeByteArray} is the same varint+bytes encoding the modern StreamCodec emits.
 *
 * <p>{@code ResourceLocation(String,String)} is still public here (privatized at 1.21 in favour of
 * {@code fromNamespaceAndPath}), so the ctor form is correct and this file needs no Cog.
 */
public final class CsLodChannel {

    public static final ResourceLocation ID =
            new ResourceLocation(CsLodProtocol.NAMESPACE, CsLodProtocol.CHANNEL);

    private CsLodChannel() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, responseSender) -> {
            // Read on the NETTY thread. The buffer is released the instant this handler returns, so the
            // bytes MUST be copied out before hopping to the main thread -- reading it inside the
            // server.execute lambda would race the release and hand us garbage (or throw).
            final byte[] data = buf.readByteArray();
            server.execute(() -> CsLodServerNet.receive(player, data));
        });

        // A token must never outlive the session that earned it.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, ignored) ->
                CsLodServerNet.onDisconnect(handler.getPlayer().getUUID()));
    }

    public static void send(ServerPlayer player, byte[] data) {
        final FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeByteArray(data);
        ServerPlayNetworking.send(player, ID, buf);
    }
}
