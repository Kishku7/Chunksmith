package com.kishku7.chunksmith.lod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
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

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Chunksmith");

    private LodInit() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(CsLodCommand.build());
    }

    /**
     * Bind Distant Horizons, at the last lifecycle point before it reports its levels.
     *
     * <p>{@code ServerAboutToStartEvent} fires from {@code MinecraftServer.runServer} BEFORE
     * {@code initServer()} -- so before {@code createLevels()}, and therefore before DH's level-load event.
     * {@code ServerStartedEvent} would already be too late to override its generator.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(final ServerAboutToStartEvent event) {
        //[[[cog
        // import cog, compat
        // if compat.has_dh(mcver, loader):
        //     cog.outl("// CsLodDhSupport hard-references Distant Horizons types, so it must not be class-loaded")
        //     cog.outl("// unless DH is actually installed. In SINGLEPLAYER the integrated server is in the client")
        //     cog.outl("// JVM, so this is the whole LOD path: no Chunksmith-Client and no network -- we hand the")
        //     cog.outl("// player's own DH its data directly.")
        //     cog.outl('if (LodPlatform.isModLoaded("distanthorizons")) {')
        //     cog.outl("    try {")
        //     cog.outl("        CsLodDhSupport.setServer(event.getServer());")
        //     cog.outl("        CsLodDhSupport.register();")
        //     cog.outl("    } catch (final LinkageError error) {")
        //     cog.outl('        LOGGER.warn("Chunksmith: Distant Horizons present but incompatible, skipping: {}", error.toString());')
        //     cog.outl("    }")
        //     cog.outl("}")
        // else:
        //     cog.outl("// No LOD renderer exists for this (loader, MC) at all, so there is nothing to bind.")
        //]]]
        //[[[end]]]
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
