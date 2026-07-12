package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.ChunksmithProvider;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.impl.GsonConfig;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers ChunkSmith as Distant Horizons' world-generator override, so DH is served straight from the
 * CSLOD store, and keeps the per-level DH wrappers the PUSH path addresses.
 *
 * <p>This is the SINGLEPLAYER path. On an integrated server the client's DH lives in the same JVM, so we
 * hand it data DIRECTLY -- no Chunksmith-Client and no network involved. On a dedicated server DH's
 * client-side engine is not there to be fed, and nothing here arms.
 *
 * <p>Hard-references DH types, so it must not be loaded unless DH is present -- {@code LodInit} owns that
 * gate ({@code LodPlatform.isModLoaded("distanthorizons")}).
 *
 * <p><b>Loader-blind.</b> Every DH symbol this class touches is {@code com.seibel.*} and names no
 * Minecraft type and no loader type, so ONE source serves Fabric, NeoForge and Forge; the only loader
 * contact is through {@link LodPlatform}. We use DH's PUBLIC API only -- no mixin into DH from this mod.
 *
 * <p>Off by default ({@code lodDhOverride}). Overriding DH's generator means DH stops generating for
 * itself: pregenerated area appears instantly, everything else returns no data. Correct for a world you
 * have pregenerated, wrong for one you have not -- so the operator has to ask for it.
 *
 * <p><b>Lifecycle, and why it looks the way it does.</b> DH fires {@link DhApiLevelLoadEvent} while the
 * server is still STARTING (from the loader's level-load event). That is before the server-started
 * lifecycle point, and the loader entrypoints only build the {@code Chunksmith} instance (and therefore
 * the config) on server-started. So at the only moment we can usefully bind, the Chunksmith singleton does
 * not exist yet -- the config flags have to be read straight off disk, and the server reference has to be
 * captured on server-starting.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodDhSupport {

    private static volatile MinecraftServer server;
    private static volatile CsLodDhGenerator lastGenerator;
    private static volatile IDhApiLevelWrapper lastWrapper;
    private static volatile boolean bound;

    /**
     * Set when the DH that is actually installed turns out not to have the API we compiled against.
     * See {@link #disable(Throwable)}.
     */
    private static volatile boolean disabled;

    /**
     * Every level wrapper DH has reported, keyed by identity of the vanilla level object it wraps.
     *
     * <p>DH loads EVERY dimension at server start -- overworld, then the nether, then the end -- firing one
     * {@link DhApiLevelLoadEvent} each. A single "last wrapper wins" field therefore ends up holding THE
     * END, and a push addressed to it lands, silently and successfully, in the end's DH database. Measured
     * on the first run of this spike: all 1089 OVERWORLD chunks were written into
     * {@code dimensions/minecraft/the_end/data/DistantHorizons.sqlite} (ChunkHash 1089, 81 of 81 detail-0
     * sections) while the overworld DB held only DH's own ordinary ingest. DH does not sanity-check the
     * dimension of the data it is handed, and reports success either way. So the pusher MUST address DH
     * per-level.
     */
    private static final Map<Object, IDhApiLevelWrapper> WRAPPERS = new ConcurrentHashMap<>();

    private CsLodDhSupport() {
    }

    /**
     * Bind DH's level-load event. Called from the loader's LOD entrypoint when DH is installed, at the
     * last point before DH fires that event.
     */
    public static void register() {
        // Bind the level-load event even when the OVERRIDE is disabled: it is also how we learn the level
        // wrappers, which the PUSH path (/cslod dhpush) needs.
        if (bound || disabled || !dhPresent()) {
            return;
        }
        bound = true;
        // Name the DH that is actually installed, in OUR log, before we touch it. We compile against the
        // standalone distanthorizonsapi artifact and support a wide DH range, so "which DH was it" is the
        // first question any bug report has to answer.
        System.out.println("[chunksmith] " + version());
        try {
            bind();
        } catch (final LinkageError e) {
            // This DH does not have the event API we compiled against. Not a crash: LOD simply does not
            // serve DH this session. Everything else in Chunksmith (including voxy) is untouched.
            disable(e);
        }
    }

    /**
     * Distant Horizons' own version, plus the API version it implements.
     *
     * <p>Read through the API surface only, and never allowed to throw: a version string is diagnostics,
     * and diagnostics must not be the thing that takes the server down.
     */
    public static String version() {
        try {
            return "Distant Horizons " + DhApi.getModVersion()
                    + " (API " + DhApi.getApiMajorVersion() + "." + DhApi.getApiMinorVersion()
                    + "." + DhApi.getApiPatchVersion() + ")";
        } catch (final RuntimeException | LinkageError e) {
            return "Distant Horizons (version unreadable: " + e + ")";
        }
    }

    /** True once DH has been ruled out for this session. */
    public static boolean isDisabled() {
        return disabled;
    }

    /**
     * Give up on DH for the rest of the session, loudly and exactly once -- but keep Chunksmith running.
     *
     * <p>A {@link LinkageError} (NoSuchMethodError / NoClassDefFoundError / AbstractMethodError -- all
     * Errors, so {@code catch (Exception)} would MISS them) means the installed DH does not match the API
     * we built against. We claim a wide DH range on the evidence that the methods we call have been
     * signature-stable since DH 2.0.0-a; this is what makes being wrong about that a logged degradation
     * rather than a crashed server.
     */
    static void disable(final Throwable cause) {
        if (disabled) {
            return;
        }
        disabled = true;
        System.out.println("[chunksmith] this Distant Horizons is not compatible with the DH API Chunksmith"
                + " was built against, so LOD will not serve DH this session -- " + cause
                + ". Everything else keeps working. Please report this along with the DH version logged"
                + " above.");
    }

    private static void bind() {
        final boolean override = enabled();

        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(final DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
                final IDhApiLevelWrapper level = event.value.levelWrapper;
                lastWrapper = level;
                final Object raw = level.getWrappedMcObject();
                if (raw != null) {
                    WRAPPERS.put(raw, level);
                }
                final Path store = storeFor(level);
                if (!override) {
                    return;
                }
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
        System.out.println("[chunksmith] Distant Horizons detected -- CSLOD level events bound");
    }

    /**
     * The server is how we translate DH's level wrapper back to a world path. Captured on server-starting,
     * which is the last lifecycle point before the levels (and so DH's level-load event) come up.
     */
    public static void setServer(final MinecraftServer current) {
        server = current;
    }

    private static Path storeFor(final IDhApiLevelWrapper level) {
        final MinecraftServer current = server;
        if (current == null) {
            return null;
        }
        // DH's wrapper exposes the underlying level object; match it against the server's levels rather
        // than trying to reconstruct a dimension id from a display name.
        final Object raw = level.getWrappedMcObject();
        for (final ServerLevel candidate : current.getAllLevels()) {
            if (candidate == raw) {
                return LodSupport.storeRoot(candidate);
            }
        }
        return null;
    }

    /** DH installed at all. The level-load event binds on this alone -- it is how we learn the wrappers. */
    private static boolean dhPresent() {
        return LodPlatform.isModLoaded("distanthorizons");
    }

    private static boolean enabled() {
        if (!dhPresent()) {
            return false;
        }
        final Config config = config();
        // DH is installed (dhPresent() above), so lodEnabled=auto resolves to ON here by definition;
        // an explicit lodEnabled=false still wins, which is the whole point of the tristate.
        return config != null && LodSupport.decide(config, server) && config.isLodDhOverrideEnabled();
    }

    /**
     * The live config if Chunksmith is already up, otherwise the config file read straight off disk -- see
     * the lifecycle note on this class. Returns null when there is no config file at all (both flags
     * default to off, so there is nothing to arm).
     */
    private static Config config() {
        if (ChunksmithProvider.isLoaded()) {
            return ChunksmithProvider.get().getConfig();
        }
        final Path configDir = LodPlatform.configDir();
        Path path = configDir.resolve("chunksmith").resolve("config.json");
        if (!Files.isRegularFile(path)) {
            // Same legacy fallback the loader entrypoints apply when they build their config.
            path = configDir.resolve("chunky").resolve("config.json");
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return new GsonConfig(path);
    }

    /**
     * The DH level wrapper for THIS level, or null if DH has not reported it.
     *
     * <p>This is the ONLY correct way to address DH from the push path -- see {@link #WRAPPERS}.
     */
    public static IDhApiLevelWrapper wrapperFor(final ServerLevel level) {
        return WRAPPERS.get(level);
    }

    /** The last level wrapper DH handed us. Diagnostics only -- never push to this. */
    public static IDhApiLevelWrapper wrapper() {
        return lastWrapper;
    }

    /** How many levels DH has reported so far. */
    public static int knownLevelCount() {
        return WRAPPERS.size();
    }

    /**
     * One-line report of what DH has actually asked us for. The ABSENCE of these counters is what let two
     * silent bugs (an override that never armed, and a null return that killed DH's queue) hide -- surface
     * them.
     */
    public static String describe() {
        final CsLodDhGenerator generator = lastGenerator;
        if (generator == null) {
            return "not serving DH (levels known: " + WRAPPERS.size() + ")";
        }
        return "serving DH: " + generator.getServedCount() + " chunks from the store, "
                + generator.getMissedCount() + " not pregenerated";
    }
}
