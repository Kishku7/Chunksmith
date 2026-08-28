package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.client.CsLodClientConfig;
import com.kishku7.chunksmith.lod.client.CsLodDimension;
import com.kishku7.chunksmith.lod.client.CsLodStore;
import com.kishku7.chunksmith.lod.client.InjectedIndex;
import com.kishku7.chunksmith.lod.client.InjectedRegions;
import com.kishku7.chunksmith.lod.client.Renderers;
import com.kishku7.chunksmith.lod.CsLodCodec;
import com.kishku7.chunksmith.lod.CsLodRegionStore;
import com.kishku7.chunksmith.lod.net.CsLodMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The store-to-renderer walk: every downloaded CSLOD record, handed to whichever renderer the player has.
 * Runs off the game thread -- rebuilding chunks and pushing them into a renderer is real work, and it must
 * never make the game stutter while the player keeps playing and their horizon fills in behind them.
 */
public final class LodInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static final long READY_TIMEOUT_MILLIS = 60_000L;

    private static final long READY_POLL_MILLIS = 250L;

    private static final AtomicLong chunks = new AtomicLong();
    private static final AtomicLong voxySections = new AtomicLong();
    private static final AtomicLong dhChunks = new AtomicLong();

    /**
     * Which injection session is current. Bumped when the session a running injection belongs to has gone
     * away -- a disconnect, a cancel, or a server that has stopped (mod_support #16).
     *
     * <p>A worker captures this value when it starts and stops as soon as it no longer matches. The level
     * check below is not enough alone: it catches the player changing dimension but not the world ending
     * underneath us -- in singleplayer the client level can still be there while the integrated server is
     * shutting down. One was seen logging "injected 17500 chunks" ~45s after "Stopping server".
     *
     * <p>A counter rather than the boolean it replaces. The boolean was set true by {@code stop()} on
     * disconnect and cleared by an {@code arm()} that only one of the two callers made -- the in-band
     * fallback. The backchannel is the path almost every player is on, so the first disconnect of a game
     * session latched the flag true and every join after it aborted at region 0 for the remaining life of
     * the process. A generation has no pairing to get wrong: a new injection reads the current value as
     * its own baseline, so there is nothing to arm and {@code arm()} is gone rather than fixed.
     */
    private static final AtomicInteger SESSION = new AtomicInteger();

    /** Ask every running injection to stop at its next region boundary. */
    public static void stop() {
        SESSION.incrementAndGet();
    }

    private LodInjector() {
    }

    /**
     * Regions already injected this session -- keyed by ({@code dimension}, x, z), never by x/z alone;
     * {@link InjectedRegions} says what keying on coordinates alone cost. Cleared on disconnect: the store
     * is keyed by server, and so is this.
     */
    private static final InjectedRegions INJECTED = new InjectedRegions();

    /**
     * The on-disk half of the same question, one per dimension we have injected into this session.
     *
     * <p>{@link #INJECTED} is emptied on disconnect, which meant a join began believing the renderer held
     * nothing -- so every region in range was decoded and pushed again into a voxy database and a DH sqlite
     * that had persisted every one of them. That is minutes of CPU per join for terrain already on screen,
     * and on a two-core machine the difference between playable and not (mod_support #15). So each
     * dimension's claims go to a {@code .injected} sidecar; this map is only a per-session handle cache.
     */
    private static final Map<String, InjectedIndex> PERSISTED =
            new ConcurrentHashMap<>();

    /**
     * Inject specific regions of a downloaded store into every renderer that is present. Skips any region
     * already injected this session, so this is safe to call on every travel refresh.
     *
     * <p>The records must belong to the level they are being pushed into, and this is where that is
     * checked. Both adapters resolve their target from the level we hand them, so the wrong dimension's
     * records are faithfully written into the right renderer for the wrong world -- and neither DH nor
     * voxy validates it. (It has happened: 1089 overworld chunks into the End's database, and in
     * 3.1.0-beta-2 the overworld's whole store into the Nether.)
     *
     * @param storeRoot the client's store for this server ({@code .../chunksmith/lod/<server>})
     * @param dimension the dimension these records belong to -- MUST be the level's own dimension
     * @param regions   the regions to inject -- typically everything the server just told us is in range
     */
    public static void injectRegions(final Path storeRoot, final String dimension,
                                     final List<CsLodMessages.RegionEntry> regions,
                                     final Consumer<String> progress) {
        // Captured together with the level, and for the same reason: both are "the world this batch of
        // records belongs to", and both are re-checked per region below.
        final int session = SESSION.get();
        final Minecraft client = Minecraft.getInstance();
        final Level level = client.level;
        if (level == null) {
            LOGGER.info("Chunksmith: no world loaded; nothing to inject");
            return;
        }

        // A download that was in flight when the player stepped through a portal lands here with the
        // dimension it was fetched for, and the level is now somewhere else entirely.
        final String levelDimension = CsLodDimension.of(level);
        if (!levelDimension.equals(dimension)) {
            LOGGER.info("Chunksmith: not injecting {} LOD data -- the player is now in {}. (Terrain from"
                            + " another dimension is not a substitute for this one's; the data for {} will"
                            + " be fetched for the level the player is actually in.)",
                    dimension, levelDimension, levelDimension);
            return;
        }

        // What did the last session hand this renderer? Seeded before the first claim of this dimension,
        // so the claims below start from the truth on disk rather than an empty map -- without it the
        // whole in-range store is "new" at every join. Null means a malformed dimension id; do not persist.
        final boolean voxyInstalled = Renderers.hasVoxy();
        final boolean dhInstalled = Renderers.hasDh();
        final InjectedIndex index = PERSISTED.computeIfAbsent(dimension, dim -> {
            final InjectedIndex opened = InjectedIndex.open(storeRoot, dim,
                    InjectedIndex.epochFor(voxyInstalled, dhInstalled, CsLodCodec.VERSION),
                    CsLodClientConfig.reinjectOnJoin());
            if (opened != null && opened.size() > 0) {
                for (long[] entry : opened.entries()) {
                    INJECTED.seed(dim, (int) entry[0], (int) entry[1], entry[2]);
                }
                LOGGER.info("Chunksmith: {} region(s) of {} were already given to this renderer in an"
                        + " earlier session; only what has changed will be injected", opened.size(), dim);
            }
            return opened;
        });

        // Claim by (dimension, region, token). An already-drawn region is skipped -- unless the server is
        // advertising a different version of it, which during a pregen is the normal case under the
        // player's feet. Keying on coordinates alone threw a re-downloaded, grown region away.
        final List<CsLodMessages.RegionEntry> fresh = new ArrayList<>();
        for (CsLodMessages.RegionEntry region : regions) {
            if (INJECTED.claim(dimension, region.regionX(), region.regionZ(), region.hash())) {
                fresh.add(region);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }

        // Wait for a renderer to become ready. On a 1 GbE LAN the whole 19 MB store downloads in under a
        // second, roughly one second before Distant Horizons announces the level; inject immediately and
        // there is nothing to inject into -- the download succeeds, the injector bails, and the player sees
        // empty sky while every log line says success.
        if (!awaitRenderer(level)) {
            // Un-mark them: a renderer that shows up later must still get this data.
            for (CsLodMessages.RegionEntry region : fresh) {
                INJECTED.release(dimension, region.regionX(), region.regionZ());
                forget(index, region);
            }
            LOGGER.info("Chunksmith: no renderer became ready within {}s (voxy={} dh={}); "
                            + "downloaded LODs are cached and will be injected on the next join",
                    READY_TIMEOUT_MILLIS / 1000, Renderers.hasVoxy(), Renderers.hasDh());
            return;
        }

        final boolean voxy = voxyInstalled && VoxyTarget.available();
        final boolean dh = dhInstalled && DhTarget.available(level);

        // Only write a claim down when we are injecting into every renderer the player has. If one is
        // installed but not ready, this pass feeds the other and the session-only claim still suppresses a
        // re-push during this session -- but nothing goes on disk, because a persisted claim would tell
        // the next join that the renderer which never received the data already has it.
        final boolean persist = index != null && voxy == voxyInstalled && dh == dhInstalled;

        // Name the dimension. It is the one fact that made the difference between "it works" and "there is
        // an ocean in the Nether", and it costs nothing to print.
        progress.accept("injecting " + fresh.size() + " new region(s) of " + dimension + " into "
                + (voxy ? "voxy " : "") + (dh ? "distant-horizons" : ""));

        final Path dir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dir == null) {
            LOGGER.warn("Chunksmith: refusing to inject a malformed dimension id");
            return;
        }
        for (int i = 0; i < fresh.size(); i++) {
            final CsLodMessages.RegionEntry region = fresh.get(i);

            // A large store is minutes of work and the player can walk into a portal half way through.
            // The level we were handed is then not the level on screen, and DH/voxy would take the rest of
            // this dimension's records into the new one. Stop, and give the untouched regions back.
            final boolean sessionEnded = SESSION.get() != session;
            if (sessionEnded || Minecraft.getInstance().level != level) {
                for (int j = i; j < fresh.size(); j++) {
                    INJECTED.release(dimension, fresh.get(j).regionX(), fresh.get(j).regionZ());
                    forget(index, fresh.get(j));
                }
                flush(index, dimension);
                // Say which of the two happened: they are different events with different fixes, and the
                // old wording announced a dimension change for both, hiding the latched-flag bug above.
                LOGGER.info("Chunksmith: stopping the {} LOD injection -- {}. {} region(s) were not"
                                + " injected and will be re-fetched {}.",
                        dimension,
                        sessionEnded ? "the session ended" : "the player is no longer in this world",
                        fresh.size() - i,
                        sessionEnded ? "on the next join" : "if the player returns");
                return;
            }

            try {
                CsLodRegionStore.forEachChunkInRegion(dir, region.regionX(), region.regionZ(),
                        record -> {
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
                // Recorded per region as it completes, not in one write at the end: a session killed
                // half way through must not lose the regions it did inject, or claim ones it did not.
                if (persist) {
                    index.put(region.regionX(), region.regionZ(), region.hash());
                }
            } catch (IOException e) {
                // Un-mark it so a later refresh retries this region rather than skipping it forever.
                INJECTED.release(dimension, region.regionX(), region.regionZ());
                forget(index, region);
                LOGGER.warn("Chunksmith: failed to read region {}.{}: {}",
                        region.regionX(), region.regionZ(), e.toString());
            }
        }

        flush(index, dimension);

        progress.accept("done -- " + chunks.get() + " chunks"
                + (voxy ? ", " + voxySections.get() + " voxy sections" : "")
                + (dh ? ", " + dhChunks.get() + " to distant-horizons (" + DhTarget.describe() + ")" : ""));

        reportDhGate(dh);
    }

    /**
     * Report when the DH dedupe gate never opened.
     *
     * <p>The mixin on {@code DhClientLevel.shouldProcessChunkUpdate} is what stops a DH server silently
     * eating our pushes (see {@link DhPushGuard}). Its config is deliberately {@code "required": false}, so
     * if the target vanishes Mixin skips it and announces that with wording like "Critical injection
     * failure" -- which reads FATAL in a user's log and is not, hence our own wording here. What we check is
     * what actually matters: we pushed chunks into a DH with a live network session and the gate was forced
     * zero times, meaning the mixin did not fire. Zero forced pushes in singleplayer (or on a server without
     * DH) is normal -- the gate returns true on its own with no network state -- so we only complain when we
     * actually pushed.
     */
    private static void reportDhGate(boolean dh) {
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

    /** Drop a region from the on-disk record. Null-safe: not every store has a writable sidecar. */
    private static void forget(InjectedIndex index, CsLodMessages.RegionEntry region) {
        if (index != null) {
            index.remove(region.regionX(), region.regionZ());
        }
    }

    /**
     * Write the record out. Failure is logged and survivable, and it fails in the safe direction: a
     * sidecar we could not write means the next join re-injects those regions, which is exactly the
     * behaviour this mechanism replaces.
     */
    private static void flush(InjectedIndex index, String dimension) {
        if (index == null) {
            return;
        }
        try {
            index.save();
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: could not record which {} LOD regions were injected ({}); they will"
                    + " be injected again on the next join", dimension, e.toString());
        }
    }

    /** Forget what we have injected. Call on disconnect. */
    public static void reset() {
        INJECTED.clear();
        // The files are deliberately left alone: they are the record the next join reads.
        PERSISTED.clear();
    }

    /**
     * Block until a renderer can actually receive data, or we give up. voxy is ready when its engine
     * exists; DH when it has fired its level-load event for this level. Both happen shortly after the
     * world loads -- and on a fast connection our download beats them.
     */
    private static boolean awaitRenderer(Level level) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if ((Renderers.hasVoxy() && VoxyTarget.available())
                    || (Renderers.hasDh() && DhTarget.available(level))) {
                return true;
            }
            try {
                Thread.sleep(READY_POLL_MILLIS);
            } catch (InterruptedException e) {
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
