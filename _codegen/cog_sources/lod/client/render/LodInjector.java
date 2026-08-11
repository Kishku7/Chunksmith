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
     * Regions already injected THIS SESSION -- keyed by ({@code dimension}, x, z), never by x/z alone.
     *
     * <p>See {@link InjectedRegions}. The short version: region (0,0) is a different place in every
     * dimension, and keying this set on coordinates alone meant the Nether's (0,0) was skipped forever the
     * moment the overworld's (0,0) had been drawn.
     *
     * <p>Cleared on disconnect: the store is keyed by server, and so is this.
     */
    private static final InjectedRegions INJECTED = new InjectedRegions();

    /**
     * The ON-DISK half of the same question, one per dimension we have injected into this session.
     *
     * <p>{@link #INJECTED} is emptied on disconnect, which meant a JOIN began believing the renderer held
     * nothing -- so every region in range was decoded and pushed again, into a voxy database and a DH
     * sqlite that had both persisted every one of them since the last session. That is minutes of CPU per
     * join for terrain that is already on screen, and on a two-core machine it is the difference between
     * playable and not (mod_support #15).
     *
     * <p>So each dimension's claims are written to a {@code .injected} sidecar and read back at the next
     * join. This map is only a per-session handle cache -- the file is the record. Cleared on disconnect
     * with everything else, because the next server gets its own store and its own sidecars.
     */
    private static final java.util.Map<String, InjectedIndex> PERSISTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Inject specific regions of a downloaded store into every renderer that is present.
     *
     * <p>Skips any region already injected this session, so this is safe to call on every travel refresh.
     *
     * <p><b>The records must belong to the level they are being pushed into.</b> Both renderer adapters
     * resolve their target from the level we hand them -- DH looks up the wrapper for THIS level, voxy calls
     * {@code WorldIdentifier.of(level)} -- so if the CALLER hands us the wrong dimension's records, they are
     * faithfully written into the right renderer for the wrong world, and neither DH nor voxy validates it.
     * DH will accept, persist, downsample and report success for every single one. (It has: 1089 overworld
     * chunks into the End's database, and in 3.1.0-beta-2 the overworld's whole store into the Nether.)
     * So the level is the authority, and this is the gate: if the dimension of these records is not the
     * dimension of the level in front of us, we do not inject. There is no "close enough" here.
     *
     * @param storeRoot the client's store for this server ({@code .../chunksmith/lod/<server>})
     * @param dimension the dimension these records belong to -- MUST be the level's own dimension
     * @param regions   the regions to inject -- typically everything the server just told us is in range
     */
    public static void injectRegions(final Path storeRoot, final String dimension,
                                     final java.util.List<CsLodMessages.RegionEntry> regions,
                                     final Consumer<String> progress) {
        final Minecraft client = Minecraft.getInstance();
        final Level level = client.level;
        if (level == null) {
            LOGGER.info("Chunksmith: no world loaded; nothing to inject");
            return;
        }

        // THE GATE. A download that was in flight when the player stepped through a portal lands here with
        // the dimension it was fetched for, and the level is now somewhere else entirely.
        final String levelDimension = CsLodDimension.of(level);
        if (!levelDimension.equals(dimension)) {
            LOGGER.info("Chunksmith: not injecting {} LOD data -- the player is now in {}. (Terrain from"
                            + " another dimension is not a substitute for this one's; the data for {} will"
                            + " be fetched for the level the player is actually in.)",
                    dimension, levelDimension, levelDimension);
            return;
        }

        // What did the LAST session hand this renderer? Seeded before the first claim of this dimension,
        // so the claims below start from the truth on disk rather than from an empty map. Without this the
        // whole in-range store is "new" at every join. Null means a malformed dimension id -- the same
        // condition the store gate below refuses on, so we simply do not persist for it.
        final boolean voxyInstalled = Renderers.hasVoxy();
        final boolean dhInstalled = Renderers.hasDh();
        final InjectedIndex index = PERSISTED.computeIfAbsent(dimension, dim -> {
            final InjectedIndex opened = InjectedIndex.open(storeRoot, dim,
                    InjectedIndex.epochFor(voxyInstalled, dhInstalled, CsLodCodec.VERSION),
                    CsLodClientConfig.reinjectOnJoin());
            if (opened != null && opened.size() > 0) {
                for (final long[] entry : opened.entries()) {
                    INJECTED.seed(dim, (int) entry[0], (int) entry[1], entry[2]);
                }
                LOGGER.info("Chunksmith: {} region(s) of {} were already given to this renderer in an"
                        + " earlier session; only what has changed will be injected", opened.size(), dim);
            }
            return opened;
        });

        // Claim by (dimension, region, TOKEN). A region we have already drawn is skipped -- unless the
        // server is now advertising a DIFFERENT version of it, which during a pregen is the NORMAL case for
        // every region under the player's feet. See InjectedRegions: keying on coordinates alone meant a
        // re-downloaded, grown region was silently thrown away by the very step that was meant to draw it.
        final java.util.List<CsLodMessages.RegionEntry> fresh = new java.util.ArrayList<>();
        for (final CsLodMessages.RegionEntry region : regions) {
            if (INJECTED.claim(dimension, region.regionX(), region.regionZ(), region.hash())) {
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
            for (final CsLodMessages.RegionEntry region : fresh) {
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

        // Only write a claim down when we are injecting into EVERY renderer the player has. If one is
        // installed but not ready yet, this pass feeds the other one and the session-only claim still
        // suppresses a pointless re-push during THIS session -- but nothing goes on disk, because a
        // persisted claim would tell the next join that the renderer which never received the data
        // already has it. A slow re-inject is the bug being fixed; a permanent hole in the horizon is
        // worse than the bug.
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

            // A large store is minutes of work on this thread, and the player keeps playing throughout --
            // they can walk into a portal half way through. The level we were handed is then no longer the
            // level on screen, and DH/voxy would take the rest of this dimension's records straight into the
            // new one. Stop, and give the untouched regions back so the re-armed pull injects them into the
            // level they belong to.
            if (Minecraft.getInstance().level != level) {
                for (int j = i; j < fresh.size(); j++) {
                    INJECTED.release(dimension, fresh.get(j).regionX(), fresh.get(j).regionZ());
                    forget(index, fresh.get(j));
                }
                flush(index, dimension);
                LOGGER.info("Chunksmith: the player left {} while its LOD data was still being injected;"
                                + " stopping here. {} region(s) were not injected and will be re-fetched if"
                                + " the player returns.", dimension, fresh.size() - i);
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
                // Recorded PER REGION, as it completes -- not in one write at the end. A session that is
                // killed half way through a large store must not lose the regions it really did inject,
                // and must not claim the ones it did not.
                if (persist) {
                    index.put(region.regionX(), region.regionZ(), region.hash());
                }
            } catch (final IOException e) {
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

    /** Drop a region from the ON-DISK record. Null-safe: not every store has a writable sidecar. */
    private static void forget(final InjectedIndex index, final CsLodMessages.RegionEntry region) {
        if (index != null) {
            index.remove(region.regionX(), region.regionZ());
        }
    }

    /**
     * Write the record out.
     *
     * <p>Failure is logged and survivable, and the direction it fails in is the safe one: a sidecar we
     * could not write means the next join re-injects those regions, which is exactly the behaviour this
     * whole mechanism replaces. Slow, not wrong.
     */
    private static void flush(final InjectedIndex index, final String dimension) {
        if (index == null) {
            return;
        }
        try {
            index.save();
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: could not record which {} LOD regions were injected ({}); they will"
                    + " be injected again on the next join", dimension, e.toString());
        }
    }

    /** Forget what we have injected. Call on disconnect. */
    public static void reset() {
        INJECTED.clear();
        // The FILES are deliberately left alone -- they are the record the next join reads. Only the
        // per-session handles go, because the next server has its own store and its own sidecars.
        PERSISTED.clear();
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
