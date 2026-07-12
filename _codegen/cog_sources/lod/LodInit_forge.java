package com.kishku7.chunksmith.lod;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The classic-Forge LOD entrypoint (MC 1.20.1 / Forge 47) -- everything LOD, and nothing else.
 *
 * <p>A GAME-bus {@code @Mod.EventBusSubscriber} rather than a hook inside {@code ChunksmithForge}: FML
 * class-loads and registers every subscriber automatically, so a cell WITH the LOD feature wires itself
 * up and a cell WITHOUT it simply does not ship this class. The general entrypoint never learns that LOD
 * exists.
 *
 * <p>The channel is built by {@code CsLodChannel}'s static initializer (a MOD-bus subscriber), because
 * Forge's network registry only accepts a new channel during mod construction.
 *
 * <p>The tick handler needs the {@link MinecraftServer}, and {@code TickEvent.ServerTickEvent} did not
 * carry one on every Forge 47 build -- so the server is CAPTURED on start rather than read off the tick
 * event. Cheap, and it removes an API question from the oldest cell we ship.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
@Mod.EventBusSubscriber(modid = "chunksmith")
public final class LodInit {

    private static volatile MinecraftServer server;

    private LodInit() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(CsLodCommand.build());
    }

    /** The HTTP backchannel binds once the server is up and its port is known. */
    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        warnOnConflicts();
        server = event.getServer();
        com.kishku7.chunksmith.lod.net.CsLodServerNet.onServerStarted(event.getServer());
    }

    /**
     * The LOD-streamer conflict check, done at RUNTIME on this cell only.
     *
     * <p>Every other LOD cell declares these as hard incompatibilities in its manifest (Fabric
     * {@code breaks}, NeoForge {@code type = "incompatible"}). Forge 47's {@code mods.toml} has NO
     * incompatible dependency type -- it only understands {@code mandatory = true|false} -- so the
     * conflict is not expressible there and has to be surfaced in the log instead.
     */
    private static void warnOnConflicts() {
        for (final String other : new String[] {"lss", "voxyserver", "lodserver"}) {
            if (net.minecraftforge.fml.ModList.get().isLoaded(other)) {
                org.slf4j.LoggerFactory.getLogger("Chunksmith").error(
                        "The mod '" + other + "' also streams LOD data to clients. Running it alongside "
                                + "Chunksmith's LOD feature means two uncoordinated writers into one LOD "
                                + "database: duplicated downloads and corrupted renderer state. Remove one.");
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        server = null;
        com.kishku7.chunksmith.lod.net.CsLodServerNet.onServerStopped();
        // Flush the writer queue and close the region files -- otherwise a pregen that ends at shutdown
        // would lose whatever was still queued.
        LodSupport.shutdown();
    }

    /** Drip-feed the in-band fallback. A few slices per tick, never a burst. */
    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final MinecraftServer current = server;
        if (current != null) {
            com.kishku7.chunksmith.lod.net.CsLodServerNet.tick(current);
        }
    }
}
