package com.kishku7.chunksmith.lod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The NeoForge LOD entrypoint -- everything LOD, and nothing else.
 *
 * <p>A GAME-bus {@code @EventBusSubscriber} rather than a hook inside {@code ChunksmithNeoForge}: FML
 * class-loads and registers every subscriber automatically, so a cell WITH the LOD feature wires itself
 * up and a cell WITHOUT it simply does not ship this class. The general entrypoint never learns that LOD
 * exists.
 *
 * <p>The payload registration is a MOD-bus event and lives in {@code CsLodChannel}.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
@EventBusSubscriber(modid = "chunksmith")
public final class LodInit {

    private LodInit() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(CsLodCommand.build());
    }

    /** The HTTP backchannel binds once the server is up and its port is known. */
    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        com.kishku7.chunksmith.lod.net.CsLodServerNet.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        com.kishku7.chunksmith.lod.net.CsLodServerNet.onServerStopped();
        // Flush the writer queue and close the region files -- otherwise a pregen that ends at shutdown
        // would lose whatever was still queued.
        LodSupport.shutdown();
    }

    /** Drip-feed the in-band fallback. A few slices per tick, never a burst. */
    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        com.kishku7.chunksmith.lod.net.CsLodServerNet.tick(event.getServer());
    }

    /** A token must never outlive the session that earned it. */
    @SubscribeEvent
    public static void onLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        com.kishku7.chunksmith.lod.net.CsLodServerNet.onDisconnect(event.getEntity().getUUID());
    }
}
