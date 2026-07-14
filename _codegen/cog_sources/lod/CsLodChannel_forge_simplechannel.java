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

/**
 * The in-band channel seam -- classic FORGE (MC 1.20.1 / Forge 47).
 *
 * <p>Forge 47 predates {@code CustomPacketPayload} entirely and has its own transport: a versioned
 * {@link SimpleChannel} built through {@code NetworkRegistry}. The channel must be built while the
 * network registry is still OPEN, which is why this class is a MOD-bus {@code @EventBusSubscriber} --
 * FML class-loads every subscriber during mod construction, and the static initializer below runs then.
 *
 * <p>The WIRE is byte-identical to every other cell: channel {@code chunksmith:lod}, one raw
 * length-prefixed byte block ({@code writeByteArray} is the same varint+bytes encoding the modern
 * StreamCodec emits). One protocol, four registration APIs. NOTE: Forge's own channel handshake adds a
 * protocol-version check on top of ours, which is harmless -- both ends run the same jar's channel.
 *
 * <p>The channel is OPTIONAL. Chunksmith is a server-side pre-generator with a client-side LOD renderer
 * bolted on; a player who does NOT run Chunksmith must still be able to join a Chunksmith server. Both
 * accepted-version predicates are wrapped in {@link NetworkRegistry#acceptMissingOr(String)}, which also
 * accepts the {@code ABSENT}/{@code ACCEPTVANILLA} sentinels the FML login handshake passes for a peer
 * that lacks the channel. A BARE {@code PROTOCOL::equals} returns false for those sentinels, which marks
 * the channel REQUIRED and makes the server REFUSE any client without Chunksmith (the client-forcing bug
 * fixed in 3.1.0-beta-5). This mirrors the NeoForge cell's {@code registrar(..).optional()} and Fabric's
 * inherently permissive play payloads -- every loader must declare the channel optional in its own API.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
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
     * {@code chunksmith:lod}.
     *
     * <p>Forge 47 PATCHES {@code new ResourceLocation(String,String)} to deprecated-for-removal (vanilla
     * 1.20.1 does not -- the identical call on the Fabric 1.20.1 cell compiles warning-free), and MC
     * 1.20.1 has no non-nullable replacement: {@code fromNamespaceAndPath} arrives at MC 1.21 (Forge only
     * backported it at 49.2 / MC 1.20.4), and {@code tryBuild}/{@code tryParse} return null, which for two
     * compile-time constants is a branch that can never be taken. So this is a narrowest-scope suppression
     * -- one method, one call -- rather than a class-level blanket or a dead null check.
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
     * Where an inbound message goes when it came FROM a server -- set by the client half at client setup,
     * {@code null} everywhere else.
     *
     * <p>This field is the side-guard. {@code Message.handle} runs on both sides, but its body names NO
     * client class: a message with a sender is a player's request (server path); a message with no sender
     * arrived from a server, and is drained into this {@code Consumer<byte[]>}. On a dedicated server
     * nothing ever sets it, that branch is dead, and {@code lod.client.*} is never class-loaded.
     */
    private static volatile java.util.function.Consumer<byte[]> clientSink;

    private CsLodChannel() {
    }

    /**
     * No-op: the channel is built by the static initializer above, which runs when FML class-loads this
     * {@code @EventBusSubscriber} during mod construction -- the only window in which the Forge network
     * registry accepts a new channel. ONE channel, ONE messageBuilder, both sides. Kept so the
     * loader-blind {@code CsLodServerNet.register()} call site is identical on every cell.
     */
    public static void register() {
    }

    /** Called by the CLIENT bootstrap only (guarded on {@code FMLEnvironment.dist == Dist.CLIENT}). */
    public static void setClientSink(final java.util.function.Consumer<byte[]> sink) {
        clientSink = sink;
    }

    /** Send raw protocol bytes to a player. */
    public static void send(final ServerPlayer player, final byte[] data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Message(data));
    }

    /** Does the connected server speak our channel? Client-side use only. */
    public static boolean isRemotePresent(final net.minecraft.network.Connection connection) {
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
                    // A player asked us for something -- the server path.
                    CsLodServerNet.receive(sender, this.data);
                    return;
                }
                // No sender: this message came FROM a server, so we are the client. Drain it into the sink
                // the client half installed. Null on a dedicated server, where this branch cannot be
                // reached anyway -- no client class is named either way.
                final java.util.function.Consumer<byte[]> sink = clientSink;
                if (sink != null) {
                    sink.accept(this.data);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /**
     * The disconnect hook. Forge's logout event is a GAME-bus event, and this class is on the MOD bus, so
     * it lives in its own nested subscriber rather than forcing a second bus registration on the class.
     */
    @Mod.EventBusSubscriber(modid = "chunksmith")
    public static final class Disconnects {

        private Disconnects() {
        }

        /** A token must never outlive the session that earned it. */
        @SubscribeEvent
        public static void onLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
            CsLodServerNet.onDisconnect(event.getEntity().getUUID());
        }
    }
}
