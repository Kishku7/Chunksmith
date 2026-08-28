package com.kishku7.chunksmith.lod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;
import net.minecraft.network.Connection;
import java.util.function.Consumer;

/**
 * The in-band channel seam -- classic Forge (MC 1.20.1 / Forge 47).
 *
 * <p>Forge 47 predates {@code CustomPacketPayload} entirely and has its own transport: a versioned
 * {@link SimpleChannel} built through {@code NetworkRegistry}. The channel must be built while the
 * network registry is still open. Hence the mod-bus {@code @EventBusSubscriber} on this class: FML
 * class-loads every subscriber during mod construction, and the static initializer below runs then.
 *
 * <p>The wire is byte-identical to every other cell: channel {@code chunksmith:lod}, one raw
 * length-prefixed byte block ({@code writeByteArray} is the same varint+bytes encoding the modern
 * StreamCodec emits). Forge's handshake adds a harmless protocol-version check of its own on top.
 *
 * <p>A player who does NOT run Chunksmith must still be able to join a Chunksmith server, so the channel
 * is optional. Both accepted-version predicates go through
 * {@link NetworkRegistry#acceptMissingOr(String)}, which also accepts the {@code ABSENT}/
 * {@code ACCEPTVANILLA} sentinels the FML login handshake passes for a peer that lacks the channel. A
 * bare {@code PROTOCOL::equals} returns false for those, marks the channel required, and makes the server
 * refuse any client without Chunksmith -- the client-forcing bug fixed in 3.1.0-beta-5.
 *
 * <p>Shared source -- canonical location _codegen/cog_sources/lod; the gen/ copy is overwritten each build.
 */
@Mod.EventBusSubscriber(modid = "chunksmith", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CsLodChannel {

    private static final String PROTOCOL = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(channelId())
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
            .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
            .simpleChannel();

    /**
     * Forge 47 patches {@code new ResourceLocation(String,String)} to deprecated-for-removal (vanilla
     * 1.20.1 does not -- the identical call on the Fabric 1.20.1 cell compiles warning-free), and MC 1.20.1
     * has no non-nullable replacement: {@code fromNamespaceAndPath} arrives at MC 1.21 (Forge backported it
     * at 49.2 / MC 1.20.4), and {@code tryBuild}/{@code tryParse} return null, which for two compile-time
     * constants is a branch that can never be taken. Hence a narrowest-scope suppression, one method.
     */
    @SuppressWarnings("removal")
    private static ResourceLocation channelId() {
        return new ResourceLocation(CsLodProtocol.NAMESPACE, CsLodProtocol.CHANNEL);
    }

    static {
        CHANNEL.messageBuilder(Message.class, 0)
                .encoder(Message::encode)
                .decoder(Message::new)
                .consumerMainThread(Message::handle)
                .add();
    }

    /**
     * Where an inbound message goes when it came from a server -- set by the client half at client setup,
     * {@code null} everywhere else. The side-guard: {@code Message.handle} runs on both sides but its body
     * names no client class. On a dedicated server nothing ever sets this, that branch is dead, and
     * {@code lod.client.*} is never class-loaded.
     */
    private static volatile Consumer<byte[]> clientSink;

    private CsLodChannel() {
    }

    /**
     * No-op: the channel is built by the static initializer above. Kept so the loader-blind
     * {@code CsLodServerNet.register()} call site is identical on every cell.
     */
    public static void register() {
    }

    /** Called by the client bootstrap only (guarded on {@code FMLEnvironment.dist == Dist.CLIENT}). */
    public static void setClientSink(final Consumer<byte[]> sink) {
        clientSink = sink;
    }

    public static void send(final ServerPlayer player, final byte[] data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Message(data));
    }

    /** Does the connected server speak our channel? Client-side use only. */
    public static boolean isRemotePresent(final Connection connection) {
        return CHANNEL.isRemotePresent(connection);
    }

    /** Send raw protocol bytes to the connected server. Client-side use only. */
    public static void sendToServer(final byte[] data) {
        CHANNEL.sendToServer(new Message(data));
    }

    /** The one and only in-band message: a raw byte block. */
    public static final class Message {

        private final byte[] data;

        Message(final byte[] data) {
            this.data = data;
        }

        Message(final FriendlyByteBuf buf) {
            this.data = buf.readByteArray();
        }

        void encode(final FriendlyByteBuf buf) {
            buf.writeByteArray(this.data);
        }

        void handle(final Supplier<NetworkEvent.Context> supplier) {
            final NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                final ServerPlayer sender = context.getSender();
                if (sender != null) {
                    CsLodServerNet.receive(sender, this.data);
                    return;
                }
                // No sender: this came from a server, so we are the client. Drain into the sink the
                // client half installed -- null on a dedicated server, where this branch cannot be reached.
                final Consumer<byte[]> sink = clientSink;
                if (sink != null) {
                    sink.accept(this.data);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /**
     * Forge's logout event is a game-bus event and this class is on the mod bus, so the disconnect hook
     * lives in its own nested subscriber rather than forcing a second bus registration on the class.
     */
    @Mod.EventBusSubscriber(modid = "chunksmith")
    public static final class Disconnects {

        private Disconnects() {
        }

        /** Drops the player's backchannel token when they log out. */
        @SubscribeEvent
        public static void onLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
            CsLodServerNet.onDisconnect(event.getEntity().getUUID());
        }
    }
}
