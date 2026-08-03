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
 * Bukkit-native counterpart to the Fabric/Forge/NeoForge {@code LodSupport}
 * (canonical: _codegen/cog_sources/lod/LodSupport.java). Resolves the active {@link LodSink} per
 * world and drives the generation hook, mirroring that class's structure and the SAME
 * {@code lodEnabled} tristate semantics -- but see the scope note below.
 *
 * <p><b>Server-side generation ONLY (2026-08-03, mod_support #9 follow-up / #11 sibling work).</b>
 * There is no renderer adapter and no client-streaming channel here yet -- that is
 * Chunksmith-Client's job on the mod loaders and does not exist on the Plugin platform. This class
 * only ever creates a {@link CsLodStoreSink}: the durable CSLOD store gets built, and nothing
 * consumes it yet. Deliberately incomplete; the streaming half is a separate, later phase.
 *
 * <p><b>The dedicated-server carve-out, simplified.</b> The mod-loader {@code decide()} treats
 * {@code AUTO} as ON when either a renderer is detected in the JVM, OR the server is a dedicated
 * server (a dedicated server cannot run voxy and does not need DH locally, but the CSLOD store is
 * exactly what a remote client downloads). A Bukkit/Paper/Folia process IS ALWAYS the dedicated-server
 * case -- there is no Bukkit integrated-server / singleplayer concept -- so here {@code AUTO} simply
 * means ON, unconditionally. No renderer detection is attempted (nothing can run one on this
 * platform), matching the 2026-08-03 scoping direction: generation now, client support later.
 */
public final class LodSupport {

    private static final Logger LOGGER = Logger.getLogger("Chunksmith");

    /** Bounded queue for the CSLOD writer thread. The generation throttle's governor keeps depth far below this. */
    private static final int WRITE_QUEUE_CAPACITY = 2048;

    private static final Map<String, LodSink> SINKS = new ConcurrentHashMap<>();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    private LodSupport() {
    }

    /** The live decision for this platform: ON unless the operator explicitly forced it off. */
    public static boolean lodEnabled(final Config config) {
        if (config == null) {
            return false;
        }
        return config.getLodMode() != LodMode.OFF;
    }

    /**
     * Offer a freshly generated chunk. Called from the generation hook on the main thread, right
     * after the chunk finishes loading (see {@code BukkitWorld#getChunkAtAsync}).
     *
     * <p>Extraction happens HERE, synchronously, for the same reason the mod-loader version does it
     * synchronously: the moment is now, and everything downstream of extraction (the writer thread)
     * is asynchronous.
     */
    public static void offer(final Config config, final World world, final Chunk chunk) {
        if (!lodEnabled(config)) {
            return;
        }
        final LodSink sink = sinkFor(config, world);
        if (sink == LodSink.NOOP) {
            return;
        }
        final CsLodChunk record = CsLodExtractor.extract(world, chunk);
        if (record != null) {
            sink.offer(record);
        }
    }

    /** The active sink for a world, resolved once. Never null. */
    public static LodSink sinkFor(final Config config, final World world) {
        final String key = world.getKey().toString();
        return SINKS.computeIfAbsent(key, ignored -> create(config, world));
    }

    private static LodSink create(final Config config, final World world) {
        final Path root = storeRoot(world);
        final CsLodStoreSink sink = new CsLodStoreSink(root, WRITE_QUEUE_CAPACITY);
        LodSinks.set(sink);
        LOGGER.info("Chunksmith: LOD store enabled -> " + root);
        return sink;
    }

    /** {@code <world>/chunksmith/lod/<dim>} -- our own tree; matches the mod-loader layout exactly. */
    public static Path storeRoot(final World world) {
        return world.getWorldFolder().toPath()
                .resolve("chunksmith").resolve("lod")
                .resolve(dimensionKey(world))
                .normalize();
    }

    /** Mirrors the mod-loader {@code dimensionKey}: the dimension id with ':' and '/' flattened. */
    public static String dimensionKey(final World world) {
        return world.getKey().toString().replace(':', '_').replace('/', '_');
    }

    /**
     * Say, once, out loud, what was decided. Called from {@code ChunksmithBukkit#onEnable}.
     */
    public static void announce(final Config config) {
        if (!ANNOUNCED.compareAndSet(false, true)) {
            return;
        }
        if (config == null) {
            return;
        }
        if (lodEnabled(config)) {
            LOGGER.info("Chunksmith: LOD generation ON (server-side CSLOD store only -- no renderer "
                    + "feed on this platform yet). Set lod-enabled: false in config.yml to turn it off.");
        } else {
            LOGGER.info("Chunksmith: LOD generation off (lod-enabled: false in config.yml).");
        }
    }

    /**
     * Flush and close every sink. Wired to {@code ChunksmithBukkit#onDisable} -- otherwise a pregen
     * that ends at shutdown would lose whatever was still queued.
     */
    public static void shutdown() {
        for (final LodSink sink : SINKS.values()) {
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
