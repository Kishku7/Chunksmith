package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.CsLodChunk;
import com.kishku7.chunksmith.lod.CsLodSectionBuilder;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feeds downloaded CSLOD records into the player's Distant Horizons.
 *
 * <p><b>We PUSH; DH does not pull from us.</b> DH's world-generator override is built only by a SERVER
 * level -- a multiplayer client gets a {@code RemoteWorldRetrievalQueue}, so {@code generateApiChunk} is
 * NEVER CALLED there. An override registered on a multiplayer client would sit there logging happily and
 * doing nothing, forever. So the client pushes instead, through
 * {@code terrainRepo.overwriteChunkDataAsync} -> {@code SharedApi.applyChunkUpdate}: the same path DH uses
 * when a player edits a block. It writes at gen step LIGHT, persists, and re-renders on its own.
 *
 * <p><b>DH bakes the light itself.</b> Its ChunkWrapper never touches Minecraft's light engine -- the push
 * path calls its own lighting engine unconditionally. So a synthesized chunk needs NO pre-lighting and no
 * populated light engine; it needs correct block states with AIR EXPLICITLY PRESENT, which CSLOD's gap-free
 * columns already guarantee.
 *
 * <p><b>Resolve the wrapper for THIS level, never "the last one".</b> DH loads EVERY dimension at startup,
 * so a single "last wrapper" field is always whichever loaded last -- and DH does NOT validate the
 * dimension of data you hand it. It will happily accept, persist and downsample overworld chunks into the
 * End's database and report success for every one. (It did exactly that, 1089 times, before this was
 * caught.)
 */
public final class DhTarget {

    /**
     * Minimum gap between pushes.
     *
     * <p>NOT arbitrary. DH's {@code ChunkUpdateQueueManager.addItemToQueue()} calls {@code popFurthest()}
     * when its queue overflows -- it evicts the entry FURTHEST FROM THE PLAYER, which is precisely the
     * distant pregenerated terrain we are trying to deliver. It is an overflow guard, not a distance
     * filter, so it only fires when pushes outrun DH's chunk-to-LOD builder. Measured safe at ~50
     * chunks/s over a 4225-chunk push with 100% retention; this pacing keeps us there.
     *
     * <p>If DH ever logs "Distant Horizons overloaded", treat it as a DATA-LOSS signal, not a warning.
     */
    private static final long MIN_PUSH_INTERVAL_NANOS = 10_000_000L;   // ~100 chunks/s ceiling

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static long lastPushNanos;

    private static final Map<Object, IDhApiLevelWrapper> WRAPPERS = new ConcurrentHashMap<>();
    private static final AtomicLong pushed = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static volatile boolean bound;

    /**
     * Set when DH turns out to be link-incompatible at runtime, which disables the DH target for the rest
     * of the session. See {@link #disable(Throwable)}.
     */
    private static volatile boolean disabled;

    private DhTarget() {
    }

    /**
     * Distant Horizons' own version + the API version it implements, for the log at join.
     *
     * <p>We compile against the standalone {@code distanthorizonsapi} artifact and support a WIDE range of
     * DH releases, so "which DH did the player actually have" is the first question any bug report raises.
     * Answer it in our own log rather than making someone reconstruct it from a crash trace.
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

    /** True once DH has been ruled out for this session; see {@link #disable(Throwable)}. */
    public static boolean isDisabled() {
        return disabled;
    }

    /**
     * Give up on DH for the rest of the session -- but keep the mod, and voxy, alive.
     *
     * <p>A {@link LinkageError} here means the DH actually installed does not have the method/type we
     * compiled against: a DH far outside the range we claim, or a fork that moved something. That is a DH
     * problem, not a reason to take the player's game or their voxy rendering down with it. So we say so
     * plainly, once, and stop touching DH.
     */
    static void disable(final Throwable cause) {
        if (disabled) {
            return;
        }
        disabled = true;
        LOGGER.warn("Chunksmith: this Distant Horizons is not compatible with the API we build"
                + " against, so we are not feeding it this session -- {}. Everything else (including voxy)"
                + " keeps working. Please report this with the DH version above.", cause.toString());
    }

    /** Learn each level's wrapper as DH loads it. Bind at mod init -- DH fires this during world load. */
    public static void bind() {
        if (bound) {
            return;
        }
        bound = true;
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(final DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
                final IDhApiLevelWrapper wrapper = event.value.levelWrapper;
                final Object raw = wrapper.getWrappedMcObject();
                if (raw != null) {
                    WRAPPERS.put(raw, wrapper);
                }
            }
        });
    }

    /** True when DH has told us about this specific level, and DH has not been ruled out this session. */
    public static boolean available(final Level level) {
        return !disabled && WRAPPERS.containsKey(level);
    }

    /**
     * Push one chunk record into DH.
     *
     * @return true if DH accepted it
     */
    public static boolean inject(final Level level, final CsLodChunk record) {
        if (disabled) {
            return false;
        }
        final IDhApiLevelWrapper wrapper = WRAPPERS.get(level);
        if (wrapper == null) {
            failed.incrementAndGet();
            return false;
        }

        final LevelChunk chunk = new LevelChunk(level, new ChunkPos(record.getChunkX(), record.getChunkZ()));
        final LevelChunkSection[] sections = chunk.getSections();
        final int count = Math.min(sections.length, record.getSections().size());
        for (int i = 0; i < count; i++) {
            sections[i] = CsLodSectionBuilder.rebuild(level, record, record.getSections().get(i));
        }

        pace();

        // Marked as OURS for the whole call: DhClientLevelMixin reads this flag inside
        // shouldProcessChunkUpdate and forces the gate open, so a DH server's ten-minute dedupe cannot eat
        // the push while still reporting success. DH's dedupe still governs every update that is not ours.
        //
        // LinkageError, not Exception: this is the FIRST and only place we call into DH's terrain repo, so
        // it is where a DH whose API does not match the one we compiled against actually blows up
        // (NoSuchMethodError / NoClassDefFoundError / AbstractMethodError -- all Errors, none of them
        // caught by `catch (Exception)`). We claim a wide DH range on the evidence that this signature has
        // been stable since DH 2.0.0-a; this catch is what makes being WRONG about that a logged
        // degradation instead of a crash.
        final DhApiResult<Void> result;
        try {
            result = DhPushGuard.pushing(() ->
                    DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(wrapper, new Object[]{chunk, level}));
        } catch (final LinkageError e) {
            disable(e);
            failed.incrementAndGet();
            return false;
        }
        if (result.success) {
            pushed.incrementAndGet();
            return true;
        }
        failed.incrementAndGet();
        return false;
    }

    /** Keep under DH's queue-overflow threshold; see MIN_PUSH_INTERVAL_NANOS. */
    private static void pace() {
        final long now = System.nanoTime();
        final long wait = MIN_PUSH_INTERVAL_NANOS - (now - lastPushNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastPushNanos = System.nanoTime();
    }

    /**
     * pushed / failed.
     *
     * <p><b>What a "success" does NOT prove.</b> {@code DhApiResult.success} means QUEUED, not WRITTEN --
     * so these counters cannot prove retention on their own. Two ways the data still disappears:
     * <ul>
     *   <li>DH's queue overflows and {@code popFurthest()} evicts the entry furthest from the player, i.e.
     *       ours. Hence the pacing above.</li>
     *   <li>On a DH-ENABLED server with real-time updates on, {@code shouldProcessChunkUpdate} silently
     *       DISCARDS an update for any position seen in the last ten minutes -- and still returns success.
     *       That gate is what the mixin turns off.</li>
     * </ul>
     * Verify retention by counting rows in DH's database, never by trusting this number.
     */
    public static String describe() {
        return "dh pushed " + pushed.get() + ", failed " + failed.get()
                + ", forced past DH's dedupe gate " + DhPushGuard.forcedCount();
    }
}
