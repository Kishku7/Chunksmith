package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.ChunksmithProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.LodMode;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves the active {@link LodSink} per world, and drives the generation hook.
 *
 * <p>Gates, in order:
 * <ol>
 *   <li><b>{@code lodEnabled} -- a TRISTATE, default {@code auto}.</b> See {@link #decide}; the decision
 *       is LOGGED once, at server start, by {@link #announce(MinecraftServer)}.</li>
 *   <li><b>CSLOD store</b> -- always on when LOD is enabled. The durable, mod-independent artifact: it
 *       outlives voxy's storage format, and it is what feeds Distant Horizons and remote clients.</li>
 *   <li><b>voxy</b> -- only where a voxy jar exists to compile against AND voxy is actually installed:
 *       Fabric 1.21.11 and Fabric 26 only. voxy is Fabric-ONLY and was never published for 1.20.1 or
 *       1.21.1, so every other cell carries the store path alone -- which is what a DEDICATED server
 *       needs anyway, voxy's engine being client-side.</li>
 * </ol>
 *
 * <p><b>Distant Horizons is not a sink.</b> DH PULLS (through the world-generator override) and is PUSHED
 * to on demand ({@code /cslod dhpush}); it is never fed from this hot path. See {@link CsLodDhSupport}.
 *
 * <p>SHARED SOURCE -- canonical location _codegen/cog_sources/lod; the gen/ copy is overwritten each build.
 */
public final class LodSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** Bounded queue for the CSLOD writer thread. The throttle's governor keeps depth far below this. */
    private static final int WRITE_QUEUE_CAPACITY = 2048;

    private static final Map<String, LodSink> SINKS = new ConcurrentHashMap<>();

    /** Presence index per dimension id. Keyed exactly as SINKS is, and as {@code World.getName()} reports. */
    private static final Map<String, CsLodPresenceIndex> PRESENCE = new ConcurrentHashMap<>();

    private LodSupport() {
    }

    /**
     * Publish the CSLOD presence provider, so the pregen's skip decision can see the store -- the whole
     * wiring for "a re-run fills LOD holes". {@code GenerationTask} lives in shared_common and cannot see
     * this class; it asks {@link LodPresence}, and this is what answers.
     */
    public static void install(final MinecraftServer server) {
        LodPresence.setProvider(worldName -> presenceIndexFor(server, worldName));
    }

    /**
     * The presence index for a world, or null when LOD generation is off. Null is load-bearing: it is how
     * {@code GenerationTask} is told "do not do any of this", which is what makes {@code lodEnabled: false}
     * restore the old skip behaviour byte for byte.
     */
    public static CsLodPresenceIndex presenceIndexFor(final MinecraftServer server, final String worldName) {
        if (server == null || !lodEnabled(server)) {
            return null;
        }
        final ServerLevel level = levelByName(server, worldName);
        if (level == null) {
            return null;
        }
        return PRESENCE.computeIfAbsent(worldName, ignored -> new CsLodPresenceIndex(storeRoot(level)));
    }

    /** The level whose dimension id matches -- the same string {@code World.getName()} returns. */
    private static ServerLevel levelByName(final MinecraftServer server, final String worldName) {
        for (final ServerLevel level : server.getAllLevels()) {
            if (dimensionId(level).equals(worldName)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Offer a freshly generated chunk. Called from the generation hook on the main thread, while the chunk
     * is still ticket-pinned: extraction happens HERE, synchronously, because the chunk is unloaded the
     * moment the ticket is released. Everything downstream of extraction is asynchronous.
     */
    public static void offer(final ServerLevel level, final LevelChunk chunk) {
        if (!lodEnabled(level.getServer())) {
            return;
        }
        final LodSink sink = sinkFor(level);
        if (sink == LodSink.NOOP) {
            return;
        }

        // voxy wants the live chunk; the CSLOD store wants an extracted record. Each sink ignores
        // what it does not recognise, so offer both.
        sink.offer(chunk);

        if (hasStore(sink)) {
            final CsLodChunk record = CsLodExtractor.extract(chunk);
            if (record != null) {
                sink.offer(record);
            }
        }
    }

    /** The active sink for a world, resolved once. Never null. */
    public static LodSink sinkFor(final ServerLevel level) {
        final String key = dimensionId(level);
        return SINKS.computeIfAbsent(key, ignored -> create(level));
    }

    /**
     * Flush and close every sink. Wired to the server-stopped lifecycle event by {@code LodInit} --
     * otherwise a pregen that ends at shutdown would lose whatever was still queued.
     */
    public static void shutdown() {
        for (final LodSink sink : SINKS.values()) {
            for (final LodSink leaf : leaves(sink)) {
                if (leaf instanceof final CsLodStoreSink store) {
                    LOGGER.info(String.format(
                            "Chunksmith: LOD store: %d chunks, %d bytes (%.1f KB/chunk), %d synchronous writes",
                            store.getWrittenCount(), store.getWrittenBytes(),
                            store.getWrittenCount() == 0 ? 0.0
                                    : store.getWrittenBytes() / 1024.0 / store.getWrittenCount(),
                            store.getSynchronousWrites()));
                    store.shutdown();
                }
            }
        }
        SINKS.clear();
        LodSinks.set(LodSink.NOOP);
        // Unpublish the presence provider with the sinks: a stale provider would hand a later run an
        // index pointing at a dead server's store root.
        LodPresence.setProvider(null);
        PRESENCE.clear();
    }

    private static LodSink create(final ServerLevel level) {
        final List<LodSink> sinks = new ArrayList<>(2);

        final Path root = storeRoot(level);
        sinks.add(new CsLodStoreSink(root, WRITE_QUEUE_CAPACITY));
        LOGGER.info("Chunksmith: LOD store enabled -> {}", root);

        //[[[cog
        // import cog, compat
        // if compat.has_voxy(mcver, loader):
        //     cog.outl('if (LodPlatform.isModLoaded("voxy")) {')
        //     cog.outl('    try {')
        //     cog.outl('        sinks.add(new VoxyLodSink());')
        //     cog.outl('        LOGGER.info("Chunksmith: voxy detected -- feeding LODs to voxy as well");')
        //     cog.outl('    } catch (final LinkageError error) {')
        //     cog.outl('        LOGGER.warn("Chunksmith: voxy present but incompatible, skipping voxy sink: {}", error.toString());')
        //     cog.outl('    }')
        //     cog.outl('}')
        // else:
        //     cog.outl("// No voxy sink on this cell: voxy is Fabric-only and upstream has never published a build")
        //     cog.outl("// for this (loader, MC), so there is nothing to compile VoxyLodSink against -- the seam is")
        //     cog.outl("// compile-time ABSENT, not stubbed. Distant Horizons still gets its LODs here (CsLodDhSupport),")
        //     cog.outl("// and a dedicated server serves the CSLOD store to Chunksmith-Client over the backchannel.")
        //]]]
        //[[[end]]]

        final LodSink sink = sinks.size() == 1 ? sinks.get(0) : new CompositeLodSink(sinks);
        LodSinks.set(sink);
        return sink;
    }

    /**
     * {@code <world>/chunksmith/lod} -- the store ROOT. This is the ONLY tree the backchannel ever serves,
     * so it is the boundary every request path is canonicalized against.
     */
    public static Path storeRootBase(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("chunksmith").resolve("lod").normalize();
    }

    /** {@code <world>/chunksmith/lod/<dim>} -- our own tree; we never touch voxy's or DH's store. */
    public static Path storeRoot(final ServerLevel level) {
        final Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        return worldRoot.resolve("chunksmith").resolve("lod").resolve(dimensionKey(level)).normalize();
    }

    /**
     * The store DIRECTORY NAME for a level -- and the name that goes on the wire: this server's store
     * directory, the dimension field of the region index, the client's own store directory, and the key
     * the client's injector checks the level against. Derived in ONE place so the two sides cannot
     * disagree.
     */
    public static String dimensionKey(final ServerLevel level) {
        return dimensionId(level).replace(':', '_').replace('/', '_');
    }

    private static String dimensionId(final ServerLevel level) {
        //[[[cog
        // import cog, compat
        // cog.outl("return level.dimension().%s().toString();" % compat.dimension_identifier_call(mcver))
        //]]]
        //[[[end]]]
    }

    private static boolean hasStore(final LodSink sink) {
        for (final LodSink leaf : leaves(sink)) {
            if (leaf instanceof CsLodStoreSink) {
                return true;
            }
        }
        return false;
    }

    private static List<LodSink> leaves(final LodSink sink) {
        if (sink instanceof final CompositeLodSink composite) {
            return composite.getSinks();
        }
        return List.of(sink);
    }


    /**
     * Mod ids that mean "something in this JVM can draw an LOD". Read out of the actual published jars /
     * fork sources on 2026-07-12:
     * <ul>
     *   <li>{@code distanthorizons} -- all loaders, every MC line we ship LOD on.</li>
     *   <li>{@code voxy} -- upstream voxy AND five of the six known forks (m3t4f1v3, j-shelfwood,
     *       realBritakee, JustinTHChapman, NHblock714), every one of which keeps the upstream mod id.</li>
     *   <li>{@code neovoxy} -- the ONE fork that renamed itself (meansabine/neo-voxy).</li>
     * </ul>
     */
    private static final String[] RENDERER_IDS = {"distanthorizons", "voxy", "neovoxy"};

    /** Resolved once: a mod cannot appear in the JVM halfway through a run. Null = none present. */
    private static volatile String renderer;
    private static volatile boolean rendererResolved;

    private static final AtomicBoolean ANNOUNCED =
            new AtomicBoolean();

    private static String detectRenderer() {
        if (!rendererResolved) {
            synchronized (LodSupport.class) {
                if (!rendererResolved) {
                    String found = null;
                    for (final String id : RENDERER_IDS) {
                        if (LodPlatform.isModLoaded(id)) {
                            found = id;
                            break;
                        }
                    }
                    renderer = found;
                    rendererResolved = true;
                }
            }
        }
        return renderer;
    }

    /**
     * Resolve the tristate. PURE -- it decides, it does not log; {@link #announce(MinecraftServer)} owns
     * the one log line.
     *
     * <p>{@code auto} is ON on a DEDICATED server even with no renderer installed, and that is deliberate:
     * a dedicated server cannot run voxy (client-only) and does not need DH, yet it is precisely where the
     * CSLOD store has to exist -- it is the thing Chunksmith-Client downloads. The cost is bounded, paid
     * only during a pregen the operator started (~5.8 KB per chunk on disk), and one config line ends it.
     *
     * <p>The wall-clock cost used to be quoted here as ~16 percent. MEASURED on a dedicated server
     * 2026-08-20 and that is NOT true any more: 36.1 cps with LOD on against 34.2 cps with it off over
     * matched windows -- no measurable difference, and the OFF run was slightly slower, which is terrain
     * noise. The reason is that extraction runs on the server thread, and once dispatch width was raised
     * the server thread stopped being the bottleneck: it profiled at ~10 percent utilised, with extraction
     * 70 percent OF THAT -- a big slice of a small pie. Do not re-introduce a scary number here without
     * re-measuring it.
     */
    public static boolean decide(final Config config,
                                 final MinecraftServer server) {
        if (config == null) {
            return false;
        }
        switch (config.getLodMode()) {
            case ON:
                return true;
            case OFF:
                return false;
            default:
                return detectRenderer() != null || (server != null && server.isDedicatedServer());
        }
    }

    /** The live decision, or false when Chunksmith is not up yet. */
    public static boolean lodEnabled(final MinecraftServer server) {
        // ChunksmithProvider.get() THROWS when unloaded, so gate on isLoaded() first.
        return ChunksmithProvider.isLoaded()
                && decide(ChunksmithProvider.get().getConfig(), server);
    }

    /**
     * Say, once, out loud, what was decided and why. Wired to server-started by {@code LodInit}; exactly
     * one of these lines per server run, and it always says which way it went.
     */
    public static void announce(final MinecraftServer server) {
        if (!ANNOUNCED.compareAndSet(false, true)) {
            return;
        }
        if (!ChunksmithProvider.isLoaded()) {
            return;
        }
        final Config config = ChunksmithProvider.get().getConfig();
        final LodMode mode = config.getLodMode();
        final String found = detectRenderer();
        final boolean on = decide(config, server);

        if (mode != LodMode.AUTO) {
            LOGGER.info("Chunksmith: LOD generation {} (lodEnabled={} set explicitly in the config{})",
                    on ? "ON" : "off", mode == LodMode.ON ? "true" : "false",
                    found == null ? "" : "; " + found + " is installed");
            return;
        }
        if (found != null) {
            LOGGER.info("Chunksmith: LOD generation auto-enabled -- detected {}. "
                            + "Pregen will build the CSLOD store (~5.8 KB/chunk; measured cost to pregen speed: none). "
                            + "Set lodEnabled=false in config/chunksmith/config.json, or run /cs set lodEnabled false, to turn it off.",
                    found);
        } else if (server != null && server.isDedicatedServer()) {
            LOGGER.info("Chunksmith: LOD generation auto-enabled -- dedicated server. No renderer runs "
                    + "here, but the CSLOD store is what Chunksmith-Client downloads, so the store is "
                    + "built (~5.8 KB/chunk; measured cost to pregen speed: none). "
                    + "Set lodEnabled=false in config/chunksmith/config.json, or run /cs set lodEnabled false, to turn it off.");
        } else {
            LOGGER.info("Chunksmith: no LOD renderer detected (looked for {}); LOD generation off. "
                    + "Install Distant Horizons or voxy, or set lodEnabled=true to force it on.",
                    String.join(", ", RENDERER_IDS));
        }
    }

    /** One-line summary for {@code /cslod status}. */
    public static String describeDecision(final MinecraftServer server) {
        if (!ChunksmithProvider.isLoaded()) {
            return "lod: unknown (chunksmith not loaded)";
        }
        final Config config = ChunksmithProvider.get().getConfig();
        final String found = detectRenderer();
        final String why;
        switch (config.getLodMode()) {
            case ON:
                why = "forced on";
                break;
            case OFF:
                why = "forced off";
                break;
            default:
                why = found != null ? "auto: " + found + " detected"
                        : (server != null && server.isDedicatedServer())
                                ? "auto: dedicated server"
                                : "auto: no renderer";
                break;
        }
        return "lod: " + (decide(config, server) ? "ON" : "off") + " (" + why + ")";
    }
}
