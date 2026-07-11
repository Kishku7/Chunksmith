package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.ChunksmithProvider;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.impl.GsonConfig;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Registers ChunkSmith as Distant Horizons' world-generator override, so DH is served straight from
 * the CSLOD store.
 *
 * <p>Hard-references DH types, so it must not be loaded unless DH is present -- {@link LodInit} owns
 * that gate.
 *
 * <p>Off by default ({@code lodDhOverride}). Overriding DH's generator means DH stops generating for
 * itself: pregenerated area appears instantly, everything else returns no data. Correct for a world
 * you have pregenerated, wrong for one you have not -- so the operator has to ask for it.
 *
 * <p><b>Lifecycle, and why it looks the way it does.</b> DH fires {@link DhApiLevelLoadEvent} from
 * Fabric's {@code ServerWorldEvents.LOAD}, i.e. while the server is still STARTING. That is before
 * {@code SERVER_STARTED}, and {@code ChunksmithFabric} only builds the {@link
 * com.kishku7.chunksmith.Chunksmith} instance (and therefore the config) on {@code SERVER_STARTED}.
 * So at the only moment we can usefully bind, the Chunksmith singleton does not exist yet -- the
 * config flags have to be read straight off disk, and the server reference has to be captured on
 * {@code SERVER_STARTING}.
 */
public final class CsLodDhSupport {

    private static volatile MinecraftServer server;
    private static volatile CsLodDhGenerator lastGenerator;

    private CsLodDhSupport() {
    }

    /**
     * Called from the Fabric entrypoint at mod init when DH is installed. The DH event has to be
     * bound this early: DH fires it during server startup, long before the Chunksmith instance
     * exists.
     */
    public static void register() {
        if (!enabled()) {
            return;
        }

        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(final DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
                final IDhApiLevelWrapper level = event.value.levelWrapper;
                final Path store = storeFor(level);
                if (store == null || !Files.isDirectory(store)) {
                    System.out.println("[chunksmith] DH loaded a level with no CSLOD store; not overriding its generator");
                    return;
                }
                final CsLodDhGenerator generator = new CsLodDhGenerator(level, store);
                lastGenerator = generator;
                DhApi.worldGenOverrides.registerWorldGeneratorOverride(level, generator);
                System.out.println("[chunksmith] serving Distant Horizons from the CSLOD store -> " + store);
            }
        });
        System.out.println("[chunksmith] Distant Horizons detected -- CSLOD world-generator override armed");
    }

    /**
     * The server is how we translate DH's level wrapper back to a world path. Captured on
     * SERVER_STARTING, which is the last lifecycle point before the levels (and so DH's level-load
     * event) come up.
     */
    public static void setServer(final MinecraftServer current) {
        server = current;
    }

    private static Path storeFor(final IDhApiLevelWrapper level) {
        final MinecraftServer current = server;
        if (current == null) {
            return null;
        }
        // DH's wrapper exposes the underlying level object; match it against the server's levels
        // rather than trying to reconstruct a dimension id from a display name.
        final Object raw = level.getWrappedMcObject();
        for (final ServerLevel candidate : current.getAllLevels()) {
            if (candidate == raw) {
                return LodSupport.storeRoot(candidate);
            }
        }
        return null;
    }

    private static boolean enabled() {
        if (!FabricLoader.getInstance().isModLoaded("distanthorizons")) {
            return false;
        }
        final Config config = config();
        return config != null && config.isLodEnabled() && config.isLodDhOverrideEnabled();
    }

    /**
     * The live config if Chunksmith is already up, otherwise the config file read straight off disk
     * -- see the lifecycle note on this class. Returns null when there is no config file at all
     * (both flags default to off, so there is nothing to arm).
     */
    private static Config config() {
        if (ChunksmithProvider.isLoaded()) {
            return ChunksmithProvider.get().getConfig();
        }
        final Path configDir = FabricLoader.getInstance().getConfigDir();
        Path path = configDir.resolve("chunksmith").resolve("config.json");
        if (!Files.isRegularFile(path)) {
            // Same legacy fallback ChunksmithFabric applies when it builds its config.
            path = configDir.resolve("chunky").resolve("config.json");
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return new GsonConfig(path);
    }

    /**
     * One-line report of what DH has actually asked us for. The ABSENCE of these counters is what let
     * two silent P3 bugs (an override that never armed, and a null return that killed DH's queue) hide
     * -- surface them.
     */
    public static String describe() {
        final CsLodDhGenerator generator = lastGenerator;
        if (generator == null) {
            return "not serving DH";
        }
        return "serving DH: " + generator.getServedCount() + " chunks from the store, "
                + generator.getMissedCount() + " not pregenerated";
    }
}
