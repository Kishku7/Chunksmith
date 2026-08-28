package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodChannel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The client-side platform facade -- Fabric, modern payload era (MC 1.20.5+).
 *
 * <p>Seam class: same package, same name, same static signatures on every loader and every MC version, so
 * the shared LOD-client tree calls {@code ClientPlatform.x()} and names no loader type. The facade is the
 * only place a loader symbol appears.
 *
 * <p>This class does NOT register the payload type. It cannot, and that is what the 3.1.0 merge bought.
 * Before it the LOD client was a separate mod, and both mods registered {@code chunksmith:lod} in
 * {@code PayloadTypeRegistry} -- so a player who had both (a self-hoster who plays singleplayer and joins
 * a friend's Chunksmith server) got
 * {@code IllegalArgumentException: Packet type ... [id=chunksmith:lod] is already registered!} and a hard
 * crash on startup. One mod now, and the type is registered exactly once, by
 * {@link CsLodChannel#register()}, from the common mod init that runs on both sides. What happens here is
 * a receiver registration against that already-registered type -- a different registry, and one that only
 * exists on a client.
 *
 * <p>Shared source -- canonical location: _codegen/cog_sources/lod/client. Edit only there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
@Environment(EnvType.CLIENT)
public final class ClientPlatform {

    private ClientPlatform() {
    }

    /** Fabric hands the client entrypoint nothing. Kept so the entrypoints have one shape across loaders. */
    public static void bootstrap(final Object bus) {
    }

    public static boolean isModLoaded(final String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    /**
     * Run once the client is far enough up to talk to other mods' APIs.
     *
     * <p>On Fabric that is the client-init entrypoint itself: client initializers run after the mod list is
     * built, and Distant Horizons' own initializer has run by the time it fires its level-load event. So
     * this is an immediate call. NeoForge and Forge defer it to {@code FMLClientSetupEvent}, where mod
     * construction can run before DH's own and {@code DhApi.events} would not be there yet.
     */
    public static void onClientSetup(final Runnable action) {
        action.run();
    }

    /**
     * Hand every server payload to {@code onPayload}, on the client thread.
     *
     * <p>Receiver only -- see the class doc. {@code CsLodChannel.Payload.TYPE} is the same type object the
     * common init registered; asking for it here neither creates nor re-registers anything.
     */
    public static void registerClientNetworking(final Consumer<byte[]> onPayload) {
        ClientPlayNetworking.registerGlobalReceiver(CsLodChannel.Payload.TYPE, (payload, context) ->
                context.client().execute(() -> onPayload.accept(payload.data())));
    }

    /** Silently does nothing when the server does not speak our channel -- which is most servers. */
    public static void sendToServer(final byte[] data) {
        if (ClientPlayNetworking.canSend(CsLodChannel.Payload.TYPE)) {
            ClientPlayNetworking.send(new CsLodChannel.Payload(data));
        }
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
