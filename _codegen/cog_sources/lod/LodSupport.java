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

/**
 * Resolves the active {@link LodSink} per world, and drives the generation hook.
 *
 * <p>Gates, in order:
 * <ol>
 *   <li><b>Opt-in.</b> {@code lodEnabled} in the config, default FALSE. LOD generation costs real
 *       pregen throughput (~2.4x slower in the 26.1.2 spike), so it is never switched on behind the
 *       operator's back.</li>
 *   <li><b>CSLOD store</b> -- always on when LOD is enabled. This is the durable, mod-independent
 *       artifact: it outlives voxy's storage format, and it is what feeds Distant Horizons and
 *       remote clients.</li>
 *   <li><b>voxy</b> -- added only where a voxy jar exists to compile against AND voxy is actually
 *       installed. Fabric 1.21.11 and Fabric 26 only: voxy is Fabric-ONLY and was never published for
 *       1.20.1 or 1.21.1, so every other cell carries the store path alone. That is exactly what a
 *       DEDICATED server needs -- voxy's engine is client-side and cannot run on one anyway.</li>
 * </ol>
 *
 * <p><b>Distant Horizons is not a sink.</b> DH PULLS (through the world-generator override) and is
 * PUSHED to on demand ({@code /cslod dhpush}); it is never fed from this hot path. See
 * {@link CsLodDhSupport}.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class LodSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** Bounded queue for the CSLOD writer thread. The throttle's governor keeps depth far below this. */
    private static final int WRITE_QUEUE_CAPACITY = 2048;

    private static final Map<String, LodSink> SINKS = new ConcurrentHashMap<>();

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
        final String key = dimensionId(level);
        return SINKS.computeIfAbsent(key, ignored -> create(level));
    }

    /**
     * Flush and close every sink. Wired to the server-stopped lifecycle event by {@code LodInit} (the
     * per-loader entrypoint) -- otherwise a pregen that ends at shutdown would lose whatever was still
     * queued.
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
        final String dim = dimensionId(level).replace(':', '_').replace('/', '_');
        return worldRoot.resolve("chunksmith").resolve("lod").resolve(dim).normalize();
    }

    /** The dimension's resource id as a string. */
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

    private static boolean lodEnabled() {
        // ChunksmithProvider.get() THROWS when unloaded, so gate on isLoaded() first.
        return ChunksmithProvider.isLoaded() && ChunksmithProvider.get().getConfig().isLodEnabled();
    }
}
