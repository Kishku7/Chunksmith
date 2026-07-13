package com.kishku7.chunksmith.lod.net;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The in-band channel seam -- FABRIC, legacy raw-channel era (MC &lt; 1.20.2).
 *
 * <p>{@code CustomPacketPayload} does not exist before 1.20.2, so there is no payload object and no
 * StreamCodec: the channel is a plain {@code (ResourceLocation, FriendlyByteBuf)} pair. The WIRE is
 * nevertheless byte-identical to the modern cells -- a length-prefixed byte array on channel
 * {@code chunksmith:lod} -- because {@code writeByteArray} is the same varint+bytes encoding the modern
 * StreamCodec emits. One protocol, four registration APIs.
 *
 * <p>{@code ResourceLocation(String,String)} is still public here (it was privatized at 1.21 in favour
 * of {@code fromNamespaceAndPath}), so the ctor form is correct and this file needs no Cog.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodChannel {

    /**
     * {@code chunksmith:lod} -- the ONE channel id, named in exactly one place.
     *
     * <p>Public because the CLIENT half of the mod ({@code lod.client.ClientPlatform}) attaches its
     * receiver to the same id. Before 3.1.0 the client was a SEPARATE mod carrying its own copy of this
     * constant; one mod, one constant, is the point of the merge.
     */
    public static final ResourceLocation ID =
            new ResourceLocation(CsLodProtocol.NAMESPACE, CsLodProtocol.CHANNEL);

    private CsLodChannel() {
    }

    /** Register the channel + the disconnect hook. Called at mod init, before any server exists. */
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

    /** Send raw protocol bytes to a player. */
    public static void send(final ServerPlayer player, final byte[] data) {
        final FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeByteArray(data);
        ServerPlayNetworking.send(player, ID, buf);
    }
}
