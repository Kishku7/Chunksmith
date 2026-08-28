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
import java.util.function.Consumer;

/**
 * The in-band channel seam -- NEOFORGE (MC 1.21+).
 *
 * <p>Same wire, different registration door: NeoForge hands out a {@link PayloadRegistrar} from a MOD-bus
 * event rather than a static registry. The MOD bus is reachable only from the {@code @Mod} constructor
 * (NeoForge injects it), and {@code @EventBusSubscriber(bus = MOD)} is DEPRECATED FOR REMOVAL as of
 * NeoForge 21.1 -- so {@link #registerPayloads(IEventBus)} is called from {@code ChunksmithNeoForge}'s
 * constructor instead, and this class carries no bus annotation at all.
 *
 * <p>SHARED SOURCE -- canonical location _codegen/cog_sources/lod; the gen/ copy is overwritten each build.
 */
public final class CsLodChannel {

    /**
     * Where an inbound CLIENTBOUND payload goes -- set by the client half at client setup, {@code null}
     * everywhere else. The side-guard: the clientbound handler below is registered on BOTH sides (it must
     * be -- see the 4-arg note), but its body names NO client class. On a dedicated server nothing ever
     * sets this, the branch is dead, and {@code lod.client.*} is never class-loaded.
     */
    private static volatile Consumer<byte[]> clientSink;

    private CsLodChannel() {
    }

    /** Called by the {@code Dist.CLIENT} entrypoint only. */
    public static void setClientSink(final Consumer<byte[]> sink) {
        clientSink = sink;
    }

    private static void dispatchClient(final byte[] data) {
        final Consumer<byte[]> sink = clientSink;
        if (sink != null) {
            sink.accept(data);
        }
    }

    /**
     * No-op on NeoForge: the payload is registered from the MOD bus (see
     * {@link #registerPayloads(IEventBus)}) and the disconnect hook is a GAME-bus event owned by
     * {@code LodInit}. Kept so the loader-blind {@code CsLodServerNet.register()} call site is identical.
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
        // Chunksmith and -- now that this jar is a client mod too -- a client would refuse any server that
        // does not. Chunksmith is client-optional AND server-optional by design.
        final PayloadRegistrar registrar = event.registrar("1").optional();

        // BIDIRECTIONAL, in ONE call, with ONE handler -- never a playToServer plus a playToClient.
        // NeoForge keys its payload registry on the payload ID, so registering chunksmith:lod twice is a
        // HARD FAILURE at load ("UnsupportedOperationException: Cannot register payload chunksmith:lod as
        // it is already registered") which trips the network-registry lock, and the server never reaches
        // Done. That is precisely what a player got with Chunksmith and the standalone Chunksmith-Client
        // both installed. (Fabric is the exact opposite: two directions, two SEPARATE registries, and both
        // MUST be registered. Same protocol, opposite registration rule.)
        //
        // The overload is version-gated, and the MERGE is why. While Chunksmith was SERVER-only the 3-arg
        // playBidirectional(TYPE, CODEC, handler) was correct everywhere: its single handler lands in the
        // serverbound slot and a dedicated server only ever receives serverbound. 3.1.0 merged the LOD
        // client in, and on 21.11+/26 a client whose clientbound slot is NULL does not warn and does not
        // degrade -- NeoForge refuses to load the mod at all ("Some clientbound payloads are missing
        // client-side handlers") and drops to the loading-error screen. So: on 21.1 ONLY the 3-arg overload
        // exists (DirectionalPayloadHandler, the 21.1-era split handler, was REMOVED by 1.21.11/26) and one
        // handler serves both directions; on 21.11+ use the 4-arg (Type, Codec, SERVERbound, CLIENTbound),
        // serverbound FIRST, so both slots are filled on both sides.
        //[[[cog
        // import cog, compat
        // for line in compat.neo_lod_registration(mcver):
        //     cog.outl(line)
        //]]]
        //[[[end]]]
    }

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
