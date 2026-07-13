package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodChannel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The CLIENT-side platform facade -- FABRIC, legacy raw-channel era (MC &lt; 1.20.2: our 1.20.1 cell).
 *
 * <p>{@code CustomPacketPayload} does not exist here, so there is no payload object and no type registry:
 * the channel is a plain {@code (ResourceLocation, FriendlyByteBuf)} pair, and registering a receiver on the
 * CLIENT side is entirely independent of the SERVER-side receiver {@link CsLodChannel#register()} installs.
 * There is therefore nothing that COULD be double-registered on this cell -- but the merged shape is the
 * same as every other cell's anyway: the channel id lives in exactly one place ({@link CsLodChannel#ID}) and
 * this class only ever attaches handlers to it.
 *
 * <p>The WIRE is byte-identical to the payload-era cells: a length-prefixed byte array on
 * {@code chunksmith:lod}. {@code writeByteArray} is the same varint+bytes encoding the modern StreamCodec
 * emits.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod/client. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
@Environment(EnvType.CLIENT)
public final class ClientPlatform {

    private ClientPlatform() {
    }

    public static void bootstrap(final Object bus) {
    }

    public static boolean isModLoaded(final String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static void onClientSetup(final Runnable action) {
        action.run();
    }

    /** Hand every server message to {@code onPayload}, on the client thread. */
    public static void registerClientNetworking(final Consumer<byte[]> onPayload) {
        ClientPlayNetworking.registerGlobalReceiver(CsLodChannel.ID, (client, handler, buf, responseSender) -> {
            // Read on the NETTY thread. The buffer is released the instant this handler returns, so the
            // bytes MUST be copied out before hopping to the main thread -- reading it inside the
            // client.execute lambda would race the release and hand us garbage (or throw). The server-side
            // twin in CsLodChannel carries the same note for the same reason.
            final byte[] data = buf.readByteArray();
            client.execute(() -> onPayload.accept(data));
        });
    }

    /** Silently does nothing when the server does not speak our channel -- which is most servers. */
    public static void sendToServer(final byte[] data) {
        if (!ClientPlayNetworking.canSend(CsLodChannel.ID)) {
            return;
        }
        final FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeByteArray(data);
        ClientPlayNetworking.send(CsLodChannel.ID, buf);
    }

    public static void onJoin(final Runnable action) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> action.run());
    }

    public static void onDisconnect(final Runnable action) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> action.run());
    }

    public static void onClientTick(final Runnable action) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> action.run());
    }
}
