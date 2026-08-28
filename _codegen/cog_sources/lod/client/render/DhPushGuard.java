package com.kishku7.chunksmith.lod.client.render;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * {@code DhClientLevel.shouldProcessChunkUpdate} -- consulted by {@code SharedApi.applyChunkUpdate},
 * which is where {@code overwriteChunkDataAsync} lands -- reads:
 *
 * <pre>
 *   if (networkState == null || !networkState.isReady()) return true;
 *   return !networkState.sessionConfig.isRealTimeUpdatesEnabled() || loadedOnceChunks.add(pos);
 * </pre>
 *
 * {@code loadedOnceChunks} is a Guava set with {@code expireAfterWrite(10, MINUTES)}, so on a DH-enabled
 * server with real-time updates on (the default) any position DH has seen in the last ten minutes makes
 * {@code add()} return false and {@code applyChunkUpdate} return early -- while the caller still returns
 * {@code DhApiResult.createSuccess()}. DH eats the push and tells us it worked.
 *
 * <p>A flag, not a config change: the DH toggles that would avoid this are not on DH's public API, and
 * reaching them means reflecting into DH's internal {@code Config$Server}, which also rewrites the
 * player's saved {@code DistantHorizons.toml}. Deliberate policy: mixin, never mutate the user's config.
 *
 * <p>ThreadLocal because {@code overwriteChunkDataAsync} calls {@code applyChunkUpdate} synchronously on
 * the calling thread (verified in DH 3.2.0's bytecode) -- "Async" is DH's internal queueing further down,
 * not the gate. {@link #forced()} counting ZERO after a push on a DH server means the gate ran somewhere
 * we did not expect and the pushes are being eaten again.
 */
public final class DhPushGuard {

    private static final ThreadLocal<Boolean> PUSHING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicLong forced = new AtomicLong();

    private DhPushGuard() {
    }

    public static <T> T pushing(final Supplier<T> push) {
        PUSHING.set(Boolean.TRUE);
        try {
            return push.get();
        } finally {
            PUSHING.set(Boolean.FALSE);
        }
    }

    public static boolean isPushing() {
        return PUSHING.get();
    }

    public static void forced() {
        forced.incrementAndGet();
    }

    public static long forcedCount() {
        return forced.get();
    }
}
