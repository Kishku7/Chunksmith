package com.kishku7.chunksmith.lod.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//[[[cog
// import cog, compat
// cog.outl(compat.identifier_import(mcver))
//]]]
//[[[end]]]
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The in-band channel seam -- NEOFORGE (MC 1.21+).
 *
 * <p>Same wire, different registration door: NeoForge hands out a {@link PayloadRegistrar} from a MOD-bus
 * event rather than a static registry. The MOD bus is reachable only from the {@code @Mod} constructor
 * (NeoForge injects it), and {@code @EventBusSubscriber(bus = MOD)} is DEPRECATED FOR REMOVAL as of
 * NeoForge 21.1 -- so {@link #registerPayloads(IEventBus)} is called from {@code ChunksmithNeoForge}'s
 * constructor instead, and this class carries no bus annotation at all.
 *
 * <p>The channel id ({@code chunksmith:lod}) and the payload (one raw byte block) are IDENTICAL to every
 * other cell -- the protocol lives in shared_common and never varies.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodChannel {

    private CsLodChannel() {
    }

    /**
     * No-op on NeoForge: the payload is registered from the MOD bus (see
     * {@link #registerPayloads(IEventBus)}), and the disconnect hook is a GAME-bus event owned by
     * {@code LodInit}. Kept so the loader-blind {@code CsLodServerNet.register()} call site is
     * identical on every cell.
     */
    public static void register() {
    }

    /** Called from the {@code @Mod} constructor -- the only place the MOD bus is handed out. */
    public static void registerPayloads(final IEventBus modBus) {
        modBus.addListener(CsLodChannel::onRegisterPayloads);
    }

    private static void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");

        // BIDIRECTIONAL, in ONE call, with ONE handler.
        //
        // The payload travels both ways -- the client sends the handshake and its requests, the server
        // sends hellos, region indexes and (on the fallback path) the data itself. Two things make this
        // the only correct shape, and BOTH were learned the hard way:
        //
        //  1. NeoForge keys its payload registry on the payload ID, so registering the SAME id with
        //     playToServer() and then playToClient() is a HARD FAILURE at load --
        //     "UnsupportedOperationException: Cannot register payload chunksmith:lod as it is already
        //     registered" -- which trips the network-registry lock and the server never reaches Done.
        //     (Fabric is the exact opposite: there the two directions are two SEPARATE registries and
        //     both MUST be registered. Same protocol, opposite registration rule.)
        //
        //  2. The 3-arg playBidirectional(TYPE, CODEC, handler) is the ONLY form present on every
        //     NeoForge we ship. DirectionalPayloadHandler (the split client/server handler used with the
        //     21.1-era API) was REMOVED by 1.21.11/26, where playBidirectional instead grew a 4-arg
        //     (client, server) overload. The 3-arg overload exists on both -- so using it means no Cog
        //     branch here at all.
        //
        // One handler serves both sides; the ServerPlayer guard is what makes that safe. On a dedicated
        // server context.player() is always a ServerPlayer. On a CLIENT it is the LocalPlayer, so the
        // guard turns this into a no-op -- exactly right, because there Chunksmith-Client owns the
        // receive side and Chunksmith itself does nothing with an inbound LOD payload.
        registrar.playBidirectional(Payload.TYPE, Payload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof final ServerPlayer player) {
                        CsLodServerNet.receive(player, payload.data());
                    }
                }));
    }

    /** Send raw protocol bytes to a player. */
    public static void send(final ServerPlayer player, final byte[] data) {
        PacketDistributor.sendToPlayer(player, new Payload(data));
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
