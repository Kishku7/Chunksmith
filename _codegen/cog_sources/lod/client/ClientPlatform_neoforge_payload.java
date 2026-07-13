package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
//[[[cog
// import cog, compat
// cog.outl(compat.neo_client_send_import(mcver))
//]]]
//[[[end]]]

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The CLIENT-side platform facade -- NEOFORGE (our 1.21.1, 1.21.11 and 26 LOD cells).
 *
 * <p>Seam twin of the Fabric copies: same package, same name, same static signatures, so the shared
 * LOD-client tree compiles unchanged.
 *
 * <p><b>THE ONE REGISTRATION -- and on NeoForge it is structurally impossible to get wrong.</b> NeoForge
 * keys its payload registry on the payload ID, so registering {@code chunksmith:lod} twice is a HARD load
 * failure ({@code UnsupportedOperationException: Cannot register payload chunksmith:lod as it is already
 * registered}). That is exactly what happened when the LOD client was a separate mod. So this class
 * registers NOTHING: {@link CsLodChannel#registerPayloads} owns the single {@code playBidirectional} call,
 * and {@link #registerClientNetworking} merely installs the CLIENT SINK that its clientbound handler drains
 * into. The common seam never names a client class; on a dedicated server the sink is simply null and the
 * clientbound branch is dead.
 *
 * <p><b>Two NeoForge API boundaries cross inside this one file,</b> both javap-proven (neoforge-21.1.233 vs
 * neoforge-21.11.42) and both emitted by {@code compat.py}:
 * <ol>
 *   <li><b>the client's send.</b> On 21.1 it is {@code PacketDistributor.sendToServer} and
 *       {@code ClientPacketDistributor} DOES NOT EXIST; by 21.11 the method has moved to
 *       {@code ClientPacketDistributor.sendToServer} and {@code PacketDistributor.sendToServer} is GONE.
 *       A hard either/or, not a deprecation.</li>
 *   <li><b>the bidirectional registration</b> -- see {@code CsLodChannel}, which owns it.</li>
 * </ol>
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod/client. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class ClientPlatform {

    /** The mod event bus, handed over by the {@code Dist.CLIENT} entrypoint. Setup events only exist here. */
    private static IEventBus modBus;

    private ClientPlatform() {
    }

    /** Called first thing by the client {@code @Mod} entrypoint -- the mod bus is reachable nowhere else. */
    public static void bootstrap(final Object bus) {
        modBus = (IEventBus) bus;
    }

    public static boolean isModLoaded(final String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /**
     * Run once the client is far enough up to talk to other mods' APIs.
     *
     * <p>NOT the {@code @Mod} constructor. NeoForge constructs mods in dependency order, and Distant
     * Horizons is a soft dependency we deliberately do not declare a load order against -- so our
     * constructor can run BEFORE DH's, and {@code DhApi.events} would not exist yet.
     * {@code FMLClientSetupEvent} runs after every mod is constructed and still long before DH fires its
     * level-load event during world load, which is the announcement we must not miss.
     */
    public static void onClientSetup(final Runnable action) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(action));
    }

    /** Install the client sink. The payload itself was registered once, by {@code CsLodChannel}. */
    public static void registerClientNetworking(final Consumer<byte[]> onPayload) {
        CsLodChannel.setClientSink(onPayload);
    }

    /** Silently does nothing when the server does not speak our channel -- which is most servers. */
    public static void sendToServer(final byte[] data) {
        final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null && NetworkRegistry.hasChannel(connection, CsLodChannel.Payload.TYPE.id())) {
            //[[[cog
            // import cog, compat
            // cog.outl(compat.neo_client_send_call(mcver, "new CsLodChannel.Payload(data)"))
            //]]]
            //[[[end]]]
        }
    }

    public static void onJoin(final Runnable action) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> action.run());
    }

    public static void onDisconnect(final Runnable action) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> action.run());
    }

    public static void onClientTick(final Runnable action) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> action.run());
    }
}
