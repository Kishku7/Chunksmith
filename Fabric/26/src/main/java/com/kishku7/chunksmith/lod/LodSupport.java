package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.ChunksmithProvider;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the active {@link LodSink} per world, and drives the generation hook.
 *
 * <p>Gates, in order:
 * <ol>
 *   <li><b>Opt-in.</b> {@code lodEnabled} in the config, default FALSE. LOD generation costs real
 *       pregen throughput (~2.4x slower in the 26.1.2 spike), so it is never switched on behind the
 *       operator's back.</li>
 *   <li><b>CSLOD store</b> -- always on when LOD is enabled. This is the durable, mod-independent
 *       artifact: it outlives voxy's storage format, and it is what will feed Distant Horizons and
 *       remote clients.</li>
 *   <li><b>voxy</b> -- added only when voxy is actually installed. {@link VoxyLodSink} hard-references
 *       voxy types, so it is not class-loaded until {@code isModLoaded("voxy")} has passed; a
 *       {@link LinkageError} degrades to no voxy sink rather than killing the pregen.</li>
 * </ol>
 */
public final class LodSupport {

    /** Bounded queue for the CSLOD writer thread. The throttle's governor keeps depth far below this. */
    private static final int WRITE_QUEUE_CAPACITY = 2048;

    private static final Map<String, LodSink> SINKS = new ConcurrentHashMap<>();
    private static volatile boolean shutdownHooked;

    private LodSupport() {
    }

    /**
     * Offer a freshly generated chunk. Called from the generation hook on the main thread, while the
     * chunk is still ticket-pinned.
     *
     * <p>Extraction happens HERE, synchronously, because the chunk is unloaded the moment the ticket
     * is released. Everything downstream of extraction is asynchronous.
     */
    public static void offer(final ServerLevel level, final LevelChunk chunk) {
        if (!lodEnabled()) {
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
        final String key = level.dimension().identifier().toString();
        return SINKS.computeIfAbsent(key, ignored -> create(level));
    }

    /** Flush and close every sink. Call at server stop. */
    public static void shutdown() {
        for (final LodSink sink : SINKS.values()) {
            for (final LodSink leaf : leaves(sink)) {
                if (leaf instanceof final CsLodStoreSink store) {
                    System.out.println(String.format(
                            "[chunksmith] LOD store: %d chunks, %d bytes (%.1f KB/chunk), %d synchronous writes",
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
    }

    private static LodSink create(final ServerLevel level) {
        // Flush the writer queue and close the region files when the server stops -- otherwise a
        // pregen that ends at shutdown would lose whatever was still queued.
        if (!shutdownHooked) {
            shutdownHooked = true;
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> shutdown());
        }

        final List<LodSink> sinks = new ArrayList<>(2);

        final Path root = storeRoot(level);
        sinks.add(new CsLodStoreSink(root, WRITE_QUEUE_CAPACITY));
        System.out.println("[chunksmith] LOD store enabled -> " + root);

        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            try {
                sinks.add(new VoxyLodSink());
                System.out.println("[chunksmith] voxy detected -- feeding LODs to voxy as well");
            } catch (final LinkageError error) {
                System.out.println("[chunksmith] voxy present but incompatible, skipping voxy sink: " + error);
            }
        }

        final LodSink sink = sinks.size() == 1 ? sinks.get(0) : new CompositeLodSink(sinks);
        LodSinks.set(sink);
        return sink;
    }

    /**
     * {@code <world>/chunksmith/lod} -- the store ROOT. This is the ONLY tree the backchannel ever serves,
     * so it is the boundary every request path is canonicalized against.
     */
    public static Path storeRootBase(final net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("chunksmith").resolve("lod").normalize();
    }

    /** {@code <world>/chunksmith/lod/<dim>} -- our own tree; we never touch voxy's or DH's store. */
    public static Path storeRoot(final ServerLevel level) {
        final Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        final String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        return worldRoot.resolve("chunksmith").resolve("lod").resolve(dim).normalize();
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

    private static boolean lodEnabled() {
        // ChunksmithProvider.get() THROWS when unloaded, so gate on isLoaded() first.
        return ChunksmithProvider.isLoaded() && ChunksmithProvider.get().getConfig().isLodEnabled();
    }
}
