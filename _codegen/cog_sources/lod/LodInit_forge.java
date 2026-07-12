package com.kishku7.chunksmith.lod;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
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

    /**
     * Bind Distant Horizons, at the last lifecycle point before it reports its levels.
     *
     * <p>{@code ServerAboutToStartEvent} fires BEFORE {@code initServer()} -- so before
     * {@code createLevels()}, and therefore before DH's level-load event. {@code ServerStartedEvent} would
     * already be too late to override its generator.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(final ServerAboutToStartEvent event) {
        //[[[cog
        // import cog, compat
        // if compat.has_dh(mcver, loader):
        //     cog.outl("// CsLodDhSupport hard-references Distant Horizons types, so it must not be class-loaded")
        //     cog.outl("// unless DH is actually installed. In SINGLEPLAYER the integrated server is in the client")
        //     cog.outl("// JVM, so this is the whole LOD path: no Chunksmith-Client and no network -- we hand the")
        //     cog.outl("// player's own DH its data directly. (DH ships a FORGE build on 1.20.1, not a NeoForge one.)")
        //     cog.outl('if (LodPlatform.isModLoaded("distanthorizons")) {')
        //     cog.outl("    try {")
        //     cog.outl("        CsLodDhSupport.setServer(event.getServer());")
        //     cog.outl("        CsLodDhSupport.register();")
        //     cog.outl("    } catch (final LinkageError error) {")
        //     cog.outl('        org.slf4j.LoggerFactory.getLogger("Chunksmith").warn(')
        //     cog.outl('                "Chunksmith: Distant Horizons present but incompatible, skipping: {}", error.toString());')
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
        warnOnConflicts();
        server = event.getServer();
        // Say what the lodEnabled tristate resolved to, and why, BEFORE anything acts on it.
        LodSupport.announce(event.getServer());
        // Make the CSLOD store visible to the pregen's skip decision, so a re-run fills LOD holes
        // instead of skipping every already-generated chunk (and so never building their LODs).
        LodSupport.install(event.getServer());
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
