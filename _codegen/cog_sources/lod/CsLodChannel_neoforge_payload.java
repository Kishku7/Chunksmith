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

    /**
     * Where an inbound CLIENTBOUND payload goes -- set by the client half at client setup, {@code null}
     * everywhere else.
     *
     * <p>This field is the side-guard. The clientbound handler below is registered on BOTH sides (it must
     * be: see the 4-arg note), but its body names NO client class -- it drains into this
     * {@code Consumer<byte[]>}. On a dedicated server nothing ever sets it, the branch is dead, and
     * {@code lod.client.*} is never class-loaded. Route the client through a static sink and a
     * {@code NoClassDefFoundError} on a headless box stops being possible rather than merely unlikely.
     */
    private static volatile java.util.function.Consumer<byte[]> clientSink;

    private CsLodChannel() {
    }

    /** Called by the {@code Dist.CLIENT} entrypoint only. */
    public static void setClientSink(final java.util.function.Consumer<byte[]> sink) {
        clientSink = sink;
    }

    /** Drain a clientbound payload. A no-op on a dedicated server, where the sink is never installed. */
    private static void dispatchClient(final byte[] data) {
        final java.util.function.Consumer<byte[]> sink = clientSink;
        if (sink != null) {
            sink.accept(data);
        }
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
        // optional() is not decoration. Without it the channel is REQUIRED, and NeoForge enforces that at
        // the handshake in BOTH directions: a server would reject every client that does not have
        // Chunksmith, and -- now that this jar is a client mod too -- a client would refuse to connect to
        // any server that does not. Chunksmith is client-optional AND server-optional by design: a
        // singleplayer user needs only the one jar, a joining player needs it client-side, an operator
        // needs it server-side, and nobody should be locked out for the other side not having it.
        final PayloadRegistrar registrar = event.registrar("1").optional();

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
        // 3-ARG vs 4-ARG -- and why the MERGE changed the answer.
        //
        // While Chunksmith was a SERVER-only mod, the 3-arg form was correct on every NeoForge we ship: a
        // dedicated server only ever receives serverbound, so the 3-arg form's single handler landing in the
        // serverbound slot was exactly right, and it exists on both 21.1 and 21.11+ -- no Cog branch.
        //
        // 3.1.0 merged the LOD client IN. This jar is now a CLIENT mod too, and on 21.11+/26 a client whose
        // clientbound slot is NULL does not warn and does not degrade: NeoForge refuses to load the mod at
        // all ("Some clientbound payloads are missing client-side handlers") and drops to the loading-error
        // screen. So the form is now version-gated:
        //   21.1   -- ONLY the 3-arg overload exists. One handler, both directions; branch on the player.
        //   21.11+ -- use the 4-arg (Type, Codec, SERVERbound, CLIENTbound) -- serverbound FIRST -- so both
        //             slots are filled on both sides.
        // Either way it is ONE playBidirectional call and NEVER a playToServer plus a playToClient: NeoForge
        // keys its payload registry on the payload id, so registering chunksmith:lod twice is a hard load
        // failure ("Cannot register payload chunksmith:lod as it is already registered"). That failure is
        // precisely what a player got when Chunksmith and the standalone Chunksmith-Client were both
        // installed -- and it is now impossible by construction, because this is the only registration of
        // this id in the mod.
        //
        // The serverbound handler's ServerPlayer guard: on a dedicated server context.player() is always a
        // ServerPlayer; on an integrated (singleplayer/LAN-host) server it is too. The clientbound handler
        // names no client class -- it drains into the static sink, which is null unless the client half
        // installed it.
        //[[[cog
        // import cog, compat
        // for line in compat.neo_lod_registration(mcver):
        //     cog.outl(line)
        //]]]
        //[[[end]]]
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
