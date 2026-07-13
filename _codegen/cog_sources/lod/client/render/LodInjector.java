package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.client.CsLodStore;
import com.kishku7.chunksmith.lod.client.Renderers;
import com.kishku7.chunksmith.lod.CsLodRegionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Walks the downloaded CSLOD store and hands every record to whichever renderer the player has.
 *
 * <p>This is the last mile: the server generated the data, the client downloaded it, and now it becomes
 * terrain the player can actually see.
 *
 * <p>Runs off the game thread. Rebuilding chunks and pushing them into a renderer is real work, and it must
 * never make the game stutter -- the player keeps playing while their horizon fills in behind them.
 */
public final class LodInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** How long to wait for a renderer to announce itself before giving up. */
    private static final long READY_TIMEOUT_MILLIS = 60_000L;

    private static final long READY_POLL_MILLIS = 250L;

    private static final AtomicLong chunks = new AtomicLong();
    private static final AtomicLong voxySections = new AtomicLong();
    private static final AtomicLong dhChunks = new AtomicLong();

    private LodInjector() {
    }

    /**
     * Region keys ({@code x,z}) already injected THIS SESSION.
     *
     * <p>The client keeps pulling as the player travels, and every pull returns the whole set of regions
     * within the renderer's radius -- most of which are already in the renderer. Injecting them again would
     * re-decode and re-push terrain that is already drawn: with voxy that is hundreds of thousands of
     * sections re-ingested per move. So a region is injected exactly once per session.
     *
     * <p>Cleared on disconnect, not on world change: the store is keyed by server, and so is this.
     */
    private static final java.util.Set<Long> INJECTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Inject specific regions of a downloaded store into every renderer that is present.
     *
     * <p>Skips any region already injected this session, so this is safe to call on every travel refresh.
     *
     * @param storeRoot the client's store for this server ({@code .../chunksmith/lod/<server>})
     * @param dimension the dimension directory inside it
     * @param regions   the regions to inject -- typically everything the server just told us is in range
     */
    public static void injectRegions(final Path storeRoot, final String dimension,
                                     final java.util.List<int[]> regions, final Consumer<String> progress) {
        final Minecraft client = Minecraft.getInstance();
        final Level level = client.level;
        if (level == null) {
            LOGGER.info("Chunksmith: no world loaded; nothing to inject");
            return;
        }

        final java.util.List<int[]> fresh = new java.util.ArrayList<>();
        for (final int[] region : regions) {
            if (INJECTED.add(key(region[0], region[1]))) {
                fresh.add(region);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }

        // WAIT for a renderer to become ready. This is not defensive padding -- it is a REAL RACE, and it
        // bit us on the first multiplayer run: on a 1 GbE LAN the whole 19 MB store downloads in under a
        // second, roughly ONE SECOND BEFORE Distant Horizons announces the level. Inject immediately and
        // there is nothing to inject into: the download succeeds, the injector bails, and the player sees
        // empty sky while every log line says success. The faster the network, the more reliably it fails.
        if (!awaitRenderer(level)) {
            // Un-mark them: a renderer that shows up later must still get this data.
            for (final int[] region : fresh) {
                INJECTED.remove(key(region[0], region[1]));
            }
            LOGGER.info("Chunksmith: no renderer became ready within {}s (voxy={} dh={}); "
                            + "downloaded LODs are cached and will be injected on the next join",
                    READY_TIMEOUT_MILLIS / 1000, Renderers.hasVoxy(), Renderers.hasDh());
            return;
        }

        final boolean voxy = Renderers.hasVoxy() && VoxyTarget.available();
        final boolean dh = Renderers.hasDh() && DhTarget.available(level);

        progress.accept("injecting " + fresh.size() + " new region(s) into "
                + (voxy ? "voxy " : "") + (dh ? "distant-horizons" : ""));

        final Path dir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dir == null) {
            LOGGER.warn("Chunksmith: refusing to inject a malformed dimension id");
            return;
        }
        for (final int[] region : fresh) {
            try {
                CsLodRegionStore.forEachChunkInRegion(dir, region[0], region[1], record -> {
                    if (voxy) {
                        voxySections.addAndGet(VoxyTarget.inject(level, record));
                    }
                    if (dh && DhTarget.inject(level, record)) {
                        dhChunks.incrementAndGet();
                    }
                    final long done = chunks.incrementAndGet();
                    if (done % 500 == 0) {
                        progress.accept("injected " + done + " chunks");
                    }
                });
            } catch (final IOException e) {
                // Un-mark it so a later refresh retries this region rather than skipping it forever.
                INJECTED.remove(key(region[0], region[1]));
                LOGGER.warn("Chunksmith: failed to read region {}.{}: {}",
                        region[0], region[1], e.toString());
            }
        }

        progress.accept("done -- " + chunks.get() + " chunks"
                + (voxy ? ", " + voxySections.get() + " voxy sections" : "")
                + (dh ? ", " + dhChunks.get() + " to distant-horizons (" + DhTarget.describe() + ")" : ""));

        reportDhGate(dh);
    }

    /**
     * Say -- IN OUR OWN WORDS -- when the DH dedupe gate never opened.
     *
     * <p>The mixin on {@code DhClientLevel.shouldProcessChunkUpdate} is what stops a DH server silently
     * eating our pushes (see {@link DhPushGuard}). Its config is deliberately {@code "required": false},
     * so if the target ever vanishes Mixin SKIPS it and the game keeps running -- correct behaviour, but
     * Mixin announces it with wording like "Critical injection failure", which reads FATAL in a user's log
     * and is not. It also does not say what it means for THIS mod.
     *
     * <p>So we check the thing that actually matters: we pushed chunks into a DH that has a live network
     * session, and the gate was forced ZERO times. That means the mixin did not fire, and DH's ten-minute
     * dedupe is free to discard our terrain while still reporting success -- the exact silent failure this
     * whole mechanism exists to prevent. Say so plainly, and say what to do about it.
     *
     * <p>Zero forced pushes in SINGLEPLAYER (or on a server without DH) is entirely normal: the gate
     * returns true on its own when there is no network state, so there is nothing to force. We therefore
     * only complain when we actually pushed something.
     */
    private static void reportDhGate(final boolean dh) {
        if (!dh || dhChunks.get() == 0 || DhPushGuard.forcedCount() > 0) {
            return;
        }
        LOGGER.info("Chunksmith: pushed {} chunks to Distant Horizons and never had to force its"
                        + " dedupe gate. On a singleplayer world or a server WITHOUT DH that is normal and"
                        + " expected. If this IS a DH-enabled server, our mixin did not fire and DH may be"
                        + " silently discarding terrain it has seen in the last 10 minutes -- please report"
                        + " it with your DH version ({}).",
                dhChunks.get(), DhTarget.version());
    }

    /** Forget what we have injected. Call on disconnect. */
    public static void reset() {
        INJECTED.clear();
    }

    private static long key(final int regionX, final int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    /**
     * Block until a renderer can actually receive data, or we give up.
     *
     * <p>voxy is ready when its engine exists; DH is ready when it has fired its level-load event for THIS
     * level. Both happen shortly after the world loads -- and on a fast connection our download beats them.
     */
    private static boolean awaitRenderer(final Level level) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if ((Renderers.hasVoxy() && VoxyTarget.available())
                    || (Renderers.hasDh() && DhTarget.available(level))) {
                return true;
            }
            try {
                Thread.sleep(READY_POLL_MILLIS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Counters -- present from the first commit, because every silent failure here looks like success. */
    public static String describe() {
        return chunks.get() + " chunks injected (" + voxySections.get() + " voxy sections, "
                + dhChunks.get() + " dh chunks)";
    }
}
