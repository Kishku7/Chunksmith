package com.kishku7.chunksmith.lod.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//[[[cog
// import cog, compat
// cog.outl(compat.identifier_import(mcver))
//]]]
//[[[end]]]
import net.minecraft.server.level.ServerPlayer;

/**
 * The in-band channel seam -- FABRIC, modern payload era (MC 1.20.5+).
 *
 * <p>Deliberately dumb. ALL the protocol lives in {@code CsLodMessages} / {@code CsLodProtocol} in
 * shared_common, which know nothing about Minecraft -- so the Chunksmith server and Chunksmith-Client
 * (a different mod, in a different repo, possibly on a different loader) share ONE implementation of the
 * wire format instead of maintaining two that drift. The same bytes also travel over the HTTP
 * backchannel and sit on disk in the store: one format, three uses.
 *
 * <p>The channel id ({@code chunksmith:lod}) and the payload shape (one raw byte block) are IDENTICAL on
 * every loader and every MC version -- only the registration API differs, and that difference stops here.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodChannel {

    private CsLodChannel() {
    }

    /** Register the channel + the disconnect hook. Called at mod init, before any server exists. */
    public static void register() {
        //[[[cog
        // import cog, compat
        // # fabric-api renamed these two accessors at its MC 26 module major (v6): playC2S/playS2C ->
        // # serverboundPlay/clientboundPlay. Nothing else in the payload path drifts.
        // cog.outl("PayloadTypeRegistry.%s().register(Payload.TYPE, Payload.CODEC);"
        //          % compat.fabric_payload_registry_call(mcver, "serverbound"))
        // cog.outl("PayloadTypeRegistry.%s().register(Payload.TYPE, Payload.CODEC);"
        //          % compat.fabric_payload_registry_call(mcver, "clientbound"))
        //]]]
        //[[[end]]]

        ServerPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, context) ->
                context.server().execute(() -> CsLodServerNet.receive(context.player(), payload.data())));

        // A token must never outlive the session that earned it.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, ignored) ->
                CsLodServerNet.onDisconnect(handler.getPlayer().getUUID()));
    }

    /** Send raw protocol bytes to a player. */
    public static void send(final ServerPlayer player, final byte[] data) {
        ServerPlayNetworking.send(player, new Payload(data));
    }

    /** The one and only in-band payload: a raw byte block. */
    public record Payload(byte[] data) implements CustomPacketPayload {

        //[[[cog
        // import cog, compat
        // cog.outl("public static final Type<Payload> TYPE = new Type<>(")
        // cog.outl("        %s);" % compat.make_id_expr(mcver, "CsLodProtocol.NAMESPACE", "CsLodProtocol.CHANNEL"))
        //]]]
        //[[[end]]]

        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
                StreamCodec.of(Payload::write, Payload::read);

        private static void write(final RegistryFriendlyByteBuf buf, final Payload payload) {
            buf.writeByteArray(payload.data());
        }

        private static Payload read(final RegistryFriendlyByteBuf buf) {
            return new Payload(buf.readByteArray());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
