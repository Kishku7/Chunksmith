package com.kishku7.chunksmith.lod;

import org.bukkit.Chunk;
import org.bukkit.World;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.LodMode;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Resolves the active {@link LodSink} per world and drives the generation hook. Mirrors the
 * Fabric/Forge/NeoForge {@code LodSupport} (canonical: _codegen/cog_sources/lod/LodSupport.java),
 * structure and {@code lodEnabled} tristate semantics alike, but see the scope note below.
 *
 * <p>Server-side generation only (mod_support #9 follow-up / #11 sibling work). There is no renderer
 * adapter and no client-streaming channel here yet. That is Chunksmith-Client's job on the mod loaders
 * and does not exist on the Plugin platform. This class only ever creates a {@link CsLodStoreSink}: the
 * durable CSLOD store gets built, and nothing consumes it yet. Deliberately incomplete; the streaming
 * half is a separate, later phase.
 *
 * <p>The dedicated-server carve-out is simpler here. The mod-loader {@code decide()} treats
 * {@code AUTO} as ON when either a renderer is detected in the JVM, OR the server is a dedicated
 * server (a dedicated server cannot run voxy and does not need DH locally, but the CSLOD store is
 * exactly what a remote client downloads). A Bukkit/Paper/Folia process is always the dedicated-server
 * case (there is no Bukkit integrated-server / singleplayer concept), so here {@code AUTO} simply
 * means ON, unconditionally. No renderer detection is attempted (nothing can run one on this
 * platform), matching the same scoping direction: generation now, client support later.
 */
public final class LodSupport {

    private static final Logger LOGGER = Logger.getLogger("Chunksmith");

    /** Bounded queue for the CSLOD writer thread. The generation throttle's governor keeps depth far below this. */
    private static final int WRITE_QUEUE_CAPACITY = 2048;

    private static final Map<String, LodSink> SINKS = new ConcurrentHashMap<>();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    private LodSupport() {
    }

    /**
     * Returns the live decision for this platform. LOD is on unless the operator explicitly forced
     * it off.
     *
     * @return true if LOD generation is enabled
     */
    public static boolean lodEnabled(Config config) {
        if (config == null) {
            return false;
        }
        return config.getLodMode() != LodMode.OFF;
    }

    /**
     * Offer a freshly generated chunk. Called from the generation hook on the main thread, right
     * after the chunk finishes loading (see {@code BukkitWorld#getChunkAtAsync}).
     *
     * <p>Extraction happens here, synchronously, for the same reason the mod-loader version does it
     * synchronously. The moment is now, and everything downstream of extraction (the writer thread)
     * is asynchronous.
     */
    public static void offer(Config config, World world, Chunk chunk) {
        if (!lodEnabled(config)) {
            return;
        }
        LodSink sink = sinkFor(config, world);
        if (sink == LodSink.NOOP) {
            return;
        }
        CsLodChunk record = CsLodExtractor.extract(world, chunk);
        if (record != null) {
            sink.offer(record);
        }
    }

    /**
     * Returns the active sink for a world, resolved once. Never null.
     *
     * @return the sink for this world
     */
    public static LodSink sinkFor(Config config, World world) {
        String key = world.getKey().toString();
        return SINKS.computeIfAbsent(key, ignored -> create(config, world));
    }

    private static LodSink create(Config config, World world) {
        Path root = storeRoot(world);
        CsLodStoreSink sink = new CsLodStoreSink(root, WRITE_QUEUE_CAPACITY);
        LodSinks.set(sink);
        LOGGER.info("Chunksmith: LOD store enabled -> " + root);
        return sink;
    }

    /**
     * Returns {@code <world>/chunksmith/lod/<dim>}, our own tree, which matches the mod-loader
     * layout exactly.
     *
     * @return the CSLOD store root for this world
     */
    public static Path storeRoot(World world) {
        return world.getWorldFolder().toPath()
                .resolve("chunksmith").resolve("lod")
                .resolve(dimensionKey(world))
                .normalize();
    }

    /** Mirrors the mod-loader {@code dimensionKey}: the dimension id with ':' and '/' flattened. */
    public static String dimensionKey(World world) {
        return world.getKey().toString().replace(':', '_').replace('/', '_');
    }

    /**
     * Say, once, out loud, what was decided. Called from {@code ChunksmithBukkit#onEnable}.
     */
    public static void announce(Config config) {
        if (!ANNOUNCED.compareAndSet(false, true)) {
            return;
        }
        if (config == null) {
            return;
        }
        if (lodEnabled(config)) {
            // Say what it means for the operator, not what the code does. The old wording ("no renderer
            // feed on this platform yet") was true and useless: what they need to know is that their
            // Players get nothing and no client-side mod will change that. mod_support #18 was somebody
            // working that out the hard way with this line already in their log.
            LOGGER.info("Chunksmith: LOD generation ON. Writing a CSLOD store, and serving it to"
                    + " players who have Chunksmith installed. Watch for the backchannel line just"
                    + " below: that port has to be reachable by your players, or they get no LOD."
                    + " Set lod-enabled: false in config.yml to turn all of this off.");
        } else {
            LOGGER.info("Chunksmith: LOD generation off (lod-enabled: false in config.yml).");
        }
    }

    /**
     * Flush and close every sink. Wired to {@code ChunksmithBukkit#onDisable} -- otherwise a pregen
     * that ends at shutdown would lose whatever was still queued.
     */
    public static void shutdown() {
        for (LodSink sink : SINKS.values()) {
            if (sink instanceof final CsLodStoreSink store) {
                LOGGER.info(String.format(
                        "Chunksmith: LOD store: %d chunks, %d bytes (%.1f KB/chunk), %d synchronous writes",
                        store.getWrittenCount(), store.getWrittenBytes(),
                        store.getWrittenCount() == 0 ? 0.0
                                : store.getWrittenBytes() / 1024.0 / store.getWrittenCount(),
                        store.getSynchronousWrites()));
                store.shutdown();
            }
        }
        SINKS.clear();
        LodSinks.set(LodSink.NOOP);
        ANNOUNCED.set(false);
    }
}
