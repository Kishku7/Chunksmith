package com.kishku7.chunksmith.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one place that says whether a pregen should hold its chunks open, for how long, and how many.
 *
 * <p>{@link ChunkSettleWindow} is deliberately MC-free and knows nothing about configuration; the
 * per-loader world adapters that own the chunk tickets know about Minecraft but have no handle on our
 * config. This is the seam between them: the generation task -- which has the config, and which is the
 * only thing that ever makes settling relevant -- states the policy here when a run starts, and each
 * world adapter asks for a window when it needs one.
 *
 * <p><b>It is also the tick pump (3.5.1).</b> Until 3.5.1 the only caller of
 * {@link ChunkSettleWindow#releaseDue} in production was the window's own {@code offer()}, so held
 * tickets came back only when a NEW chunk arrived. That is fine while a run is flowing and wrong the
 * moment it is not: when the residency gate holds dispatch there are no arrivals, so the frontier
 * cannot shrink, so residency cannot fall, so the gate stays shut -- the gate suppressed its own
 * recovery. Every live window is registered here and pumped once per server tick instead, which makes
 * a release depend on TIME PASSING rather than on work being dispatched.
 *
 * <p>Static because a chunk ticket is server-thread state and there is exactly one server per process.
 * Volatile because the task is constructed off the main thread while the adapters read from it on the
 * server thread. The registry is copy-on-write: it is written once per world per run and read every
 * tick, and a tick must never block behind a window being created.
 */
public final class ChunkSettleSupport {

    private static volatile boolean enabled;
    private static volatile long delayTicks;
    private static volatile long maxHeld;

    private static final List<ChunkSettleWindow> LIVE = new CopyOnWriteArrayList<>();

    private ChunkSettleSupport() {
    }

    /** Called when a generation task starts, from the config that task was created with. */
    public static void configure(final boolean enabled, final long delayTicks, final long maxHeld) {
        ChunkSettleSupport.enabled = enabled;
        ChunkSettleSupport.delayTicks = Math.max(0L, delayTicks);
        ChunkSettleSupport.maxHeld = Math.max(0L, maxHeld);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * A fresh window for one world, or {@code null} when settling is off.
     *
     * <p>Null rather than a do-nothing window on purpose: the caller's null check is what restores the
     * original code path exactly -- release the ticket inline, allocate nothing, add no indirection --
     * for the operator who has turned this off precisely because they want none of it.
     */
    public static ChunkSettleWindow newWindow() {
        if (!enabled) {
            return null;
        }
        final ChunkSettleWindow window = new ChunkSettleWindow(delayTicks, maxHeld);
        LIVE.add(window);
        return window;
    }

    /**
     * Release anything whose delay has elapsed, in every live window. Called once per server tick from
     * the platform, on the server thread -- the only thread allowed to touch a chunk ticket.
     *
     * <p>Drained windows are dropped here rather than by the adapter: a window is finished when it says
     * it is, and making the adapter remember to deregister would be exactly the kind of pairing that
     * {@code LodInjector.arm()} taught us not to write.
     *
     * @param gameTime the current game tick, the same clock {@code offer()} is given
     */
    public static void tick(final long gameTime) {
        if (LIVE.isEmpty()) {
            return;
        }
        for (final ChunkSettleWindow window : LIVE) {
            if (window.isDrained()) {
                LIVE.remove(window);
                continue;
            }
            window.releaseDue(gameTime);
        }
    }

    // flushAll() REMOVED (2026-08-20). It handed back every held ticket at once, and its only caller
    // was the dispatch loop -- which runs on the Chunksmith WORKER thread. Releasing a ticket calls
    // removeTicketWithRadius, and the server thread is the only thread allowed to touch a chunk
    // ticket (see ChunkSettleWindow's javadoc; mod_support #16 is that rule being broken). It
    // corrupted the fastutil ticket graph on a live server and killed it via
    // ArrayIndexOutOfBoundsException in Long2ByteOpenHashMap.rehash plus a 60-second tick.
    //
    // Nothing replaces it: the frontier is bounded by pregenSettleMaxHeld and released by tick() on
    // the server thread, which is where ticket work belongs.

    /** How many windows are live. Test-visible, because a registry that leaks is a ticket leak. */
    public static int liveWindowCount() {
        return LIVE.size();
    }

    /** Drop every registered window without draining it. The server is going away; see Chunksmith#disable. */
    public static void forget() {
        LIVE.clear();
    }
}
