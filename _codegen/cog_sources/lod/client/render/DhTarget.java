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
 * <p>We push; DH does not pull from us. DH's world-generator override is built only by a server level --
 * a multiplayer client gets a {@code RemoteWorldRetrievalQueue}, so {@code generateApiChunk} is NEVER
 * called there. The client pushes instead, through {@code terrainRepo.overwriteChunkDataAsync}
 * -> {@code SharedApi.applyChunkUpdate}: the same path DH uses when a player edits a block. It writes at
 * gen step LIGHT, persists, and re-renders on its own.
 *
 * <p>A synthesized chunk needs no pre-lighting. DH bakes the light itself -- its ChunkWrapper never
 * touches Minecraft's light engine, and the push path calls its own lighting engine unconditionally.
 * What it does need is correct block states with air explicitly present, which CSLOD's gap-free columns
 * guarantee.
 *
 * <p><b>Resolve the wrapper for this level, never "the last one".</b> DH loads every dimension at
 * startup and does not validate the dimension of data you hand it: it will happily accept, persist and
 * downsample overworld chunks into the End's database and report success for every one. (It did exactly
 * that, 1089 times, before this was caught.)
 */
public final class DhTarget {

    /**
     * Minimum gap between pushes. Measured safe at ~50 chunks/s over a 4225-chunk push, 100% retention.
     *
     * <p>Not arbitrary: DH's {@code ChunkUpdateQueueManager.addItemToQueue()} calls {@code popFurthest()}
     * when its queue overflows, evicting the entry furthest from the player -- precisely the distant
     * pregenerated terrain we are delivering. It is an overflow guard, not a distance filter, so it fires
     * only when pushes outrun DH's chunk-to-LOD builder. "Distant Horizons overloaded" in the log means
     * data was lost, not that something was slow.
     */
    private static final long MIN_PUSH_INTERVAL_NANOS = 10_000_000L;   // ~100 chunks/s ceiling

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static long lastPushNanos;

    private static final Map<Object, IDhApiLevelWrapper> WRAPPERS = new ConcurrentHashMap<>();
    private static final AtomicLong pushed = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static volatile boolean bound;

    /** Set when DH is link-incompatible at runtime; see {@link #disable(Throwable)}. */
    private static volatile boolean disabled;

    private DhTarget() {
    }

    /**
     * Distant Horizons' own version + the API version it implements, for the log at join. We compile
     * against the standalone {@code distanthorizonsapi} artifact and support a wide range of DH releases,
     * so "which DH did the player actually have" is the first question any bug report raises.
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

    public static boolean isDisabled() {
        return disabled;
    }

    /**
     * Give up on DH for the session. A {@link LinkageError} means the installed DH lacks a method or type
     * we compiled against -- a DH problem, and no reason to take the player's game or their voxy with it.
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

    public static boolean available(final Level level) {
        return !disabled && WRAPPERS.containsKey(level);
    }

    /** @return true if DH accepted this record. */
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

        // Marked as ours for the whole call: DhClientLevelMixin forces the dedupe gate open for this
        // flag, so a DH server's ten-minute dedupe cannot eat the push while still reporting success (see
        // DhPushGuard).
        //
        // LinkageError, not Exception: this is the only place we call into DH's terrain repo, so it is
        // where a DH whose API does not match ours blows up (NoSuchMethodError / NoClassDefFoundError /
        // AbstractMethodError -- all Errors, none caught by `catch (Exception)`). We claim a wide DH range
        // on the evidence that this signature has been stable since DH 2.0.0-a.
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
     * <p>{@code DhApiResult.success} means queued, not written, so these counters cannot prove retention.
     * Two ways the data still disappears: DH's queue overflows and {@code popFurthest()} evicts the entry
     * furthest from the player, i.e. ours (hence the pacing above); and on a DH-enabled server with
     * real-time updates on, {@code shouldProcessChunkUpdate} silently discards an update for any position
     * seen in the last ten minutes while still returning success (the gate the mixin turns off). Count
     * rows in DH's database to check retention.
     */
    public static String describe() {
        return "dh pushed " + pushed.get() + ", failed " + failed.get()
                + ", forced past DH's dedupe gate " + DhPushGuard.forcedCount();
    }
}
