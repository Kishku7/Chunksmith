package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The CLIENT-side platform facade -- classic FORGE (MC 1.20.1 / Forge 47), the SimpleChannel era.
 *
 * <p><b>THE ONE REGISTRATION.</b> Forge 47 predates {@code CustomPacketPayload}: the transport is a
 * versioned {@code SimpleChannel} built through {@code NetworkRegistry}, and it must be built while the
 * network registry is still OPEN (mod construction). {@link CsLodChannel} owns that -- ONE channel, ONE
 * {@code messageBuilder}, built by its static initializer on both sides. This class registers nothing; it
 * installs the CLIENT SINK that {@code CsLodChannel.Message.handle} drains into when the message arrived
 * from a server ({@code context.getSender() == null}). On a dedicated server the sink is never set and that
 * branch is dead.
 *
 * <p>Forge's SimpleChannel prefixes every message with a discriminator byte that a raw Fabric channel does
 * not, so a Forge client is wire-compatible with a Forge server and a Fabric client with a Fabric server.
 * Chunksmith ships BOTH loaders on 1.20.1, so both pairings exist; a Forge client on a Fabric 1.20.1 server
 * never completes the SimpleChannel handshake, {@link #sendToServer} sees no remote channel, and the LOD
 * client stays quiet -- exactly what it does on any server that is not running Chunksmith.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod/client. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class ClientPlatform {

    private ClientPlatform() {
    }

    /** Forge hands the client bootstrap nothing it needs. Kept so the entrypoints have one shape. */
    public static void bootstrap(final Object bus) {
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
     * <p>On this loader we are ALREADY there: {@code LodClientInit} is a {@code Dist.CLIENT} MOD-bus
     * subscriber whose only handler is {@code FMLClientSetupEvent}, and it is what called into here. So this
     * is an immediate call, and the loader -- not a runtime check -- is what kept us off the server.
     */
    public static void onClientSetup(final Runnable action) {
        action.run();
    }

    /** Install the client sink. The channel itself was built once, by {@code CsLodChannel}. */
    public static void registerClientNetworking(final Consumer<byte[]> onPayload) {
        CsLodChannel.setClientSink(onPayload);
    }

    /** Silently does nothing when the server does not speak our channel -- which is most servers. */
    public static void sendToServer(final byte[] data) {
        final ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null || !CsLodChannel.isRemotePresent(listener.getConnection())) {
            return;
        }
        CsLodChannel.sendToServer(data);
    }

    public static void onJoin(final Runnable action) {
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> action.run());
    }

    public static void onDisconnect(final Runnable action) {
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> action.run());
    }

    public static void onClientTick(final Runnable action) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                action.run();
            }
        });
    }
}
