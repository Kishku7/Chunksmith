package com.kishku7.chunksmith.lod.client.render;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Marks the thread that is currently pushing OUR data into Distant Horizons.
 *
 * <p><b>Why this exists.</b> {@code DhClientLevel.shouldProcessChunkUpdate} -- consulted by
 * {@code SharedApi.applyChunkUpdate}, which is where {@code overwriteChunkDataAsync} lands -- reads:
 *
 * <pre>
 *   if (networkState == null || !networkState.isReady()) return true;
 *   return !networkState.sessionConfig.isRealTimeUpdatesEnabled() || loadedOnceChunks.add(pos);
 * </pre>
 *
 * {@code loadedOnceChunks} is a Guava set with {@code expireAfterWrite(10, MINUTES)}. So on a DH-ENABLED
 * server with real-time updates on (the DEFAULT), any chunk position DH has seen in the last ten minutes
 * makes {@code add()} return false, {@code applyChunkUpdate} returns early -- and the caller STILL returns
 * {@code DhApiResult.createSuccess()}. DH eats the push and tells us it worked. Exactly the terrain around
 * a joining player -- the terrain they are most likely to look at -- is the terrain DH has just seen.
 *
 * <p><b>Why a flag and not a config change.</b> The three DH toggles that would avoid this
 * ({@code enableRealTimeUpdates} and friends) are not on DH's public API, so reaching them means reflecting
 * into DH's internal {@code Config$Server} -- which also rewrites the player's saved
 * {@code DistantHorizons.toml}. Deliberate policy: mixin, never mutate the user's config. This flag keeps the
 * mixin surgical -- DH's dedupe still applies to every update that is not ours.
 *
 * <p>The flag is a ThreadLocal because {@code overwriteChunkDataAsync} calls {@code applyChunkUpdate}
 * synchronously on the calling thread (verified in DH 3.2.0's bytecode) -- "Async" describes DH's internal
 * queueing further down, not the gate. {@link #forced()} counts how often the mixin actually fired: if we
 * push on a DH server and that count is ZERO, the gate ran somewhere we did not expect and the pushes are
 * being eaten again. Never let this fail silently -- that is the whole history of this feature.
 */
public final class DhPushGuard {

    private static final ThreadLocal<Boolean> PUSHING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicLong forced = new AtomicLong();

    private DhPushGuard() {
    }

    /** Run a push with the gate bypassed for THIS thread only. */
    public static <T> T pushing(final java.util.function.Supplier<T> push) {
        PUSHING.set(Boolean.TRUE);
        try {
            return push.get();
        } finally {
            PUSHING.set(Boolean.FALSE);
        }
    }

    /** Read by the mixin on DhClientLevel. */
    public static boolean isPushing() {
        return PUSHING.get();
    }

    /** Called by the mixin every time it forces an update past DH's dedupe gate. */
    public static void forced() {
        forced.incrementAndGet();
    }

    /** How many pushes we carried past the gate. Zero on a non-DH server is normal; see the class doc. */
    public static long forcedCount() {
        return forced.get();
    }
}
