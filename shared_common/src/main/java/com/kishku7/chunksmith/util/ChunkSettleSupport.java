package com.kishku7.chunksmith.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Seam between {@link ChunkSettleWindow}, which knows nothing about Minecraft or about config, and the
 * per-loader adapters that own the chunk tickets but have no handle on it. The hold policy (whether,
 * how long, how many) is read here.
 *
 * <p><b>It is also the tick pump (3.5.1).</b> Until 3.5.1 the only production caller of
 * {@link ChunkSettleWindow#releaseDue} was the window's own {@code offer()}, so when the residency gate
 * held dispatch there were no arrivals, the frontier could not shrink, residency could not fall, and the
 * gate suppressed its own recovery. Every live window is pumped once per server tick instead.
 *
 * <p>Static because a chunk ticket is server-thread state and there is one server per process. Volatile
 * because the task is constructed off the main thread while adapters read on the server thread. The
 * registry is copy-on-write: written once per world per run, read every tick, never blocking a tick.
 */
public final class ChunkSettleSupport {

    private static volatile boolean enabled;
    private static volatile long delayTicks;
    private static volatile long maxHeld;

    private static final List<ChunkSettleWindow> LIVE = new CopyOnWriteArrayList<>();

    private ChunkSettleSupport() {
    }

    /** Sets the settle policy. Called when a task starts, from the config it was created with. */
    public static void configure(boolean enabled, long delayTicks, long maxHeld) {
        ChunkSettleSupport.enabled = enabled;
        ChunkSettleSupport.delayTicks = Math.max(0L, delayTicks);
        ChunkSettleSupport.maxHeld = Math.max(0L, maxHeld);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Creates a fresh window for one world, or returns {@code null} when settling is off. Null rather than
     * a do-nothing window on purpose, because the caller's null check is what restores the original code
     * path exactly (release the ticket inline, allocate nothing) for the operator who has turned this off.
     */
    public static ChunkSettleWindow newWindow() {
        if (!enabled) {
            return null;
        }
        ChunkSettleWindow window = new ChunkSettleWindow(delayTicks, maxHeld);
        LIVE.add(window);
        return window;
    }

    /**
     * Releases anything whose delay has elapsed, in every live window. Called once per server tick from
     * the platform, on the server thread, the only thread allowed to touch a chunk ticket. Drained
     * windows are dropped here rather than by the adapter: making the adapter remember to deregister
     * would be exactly the kind of pairing that {@code LodInjector.arm()} taught us not to write.
     */
    public static void tick(long gameTime) {
        if (LIVE.isEmpty()) {
            return;
        }
        for (ChunkSettleWindow window : LIVE) {
            if (window.isDrained()) {
                LIVE.remove(window);
                continue;
            }
            window.releaseDue(gameTime);
        }
    }

    // flushAll() REMOVED (2026-08-20). It handed back every held ticket at once, and its only caller
    // was the dispatch loop -- which runs on the Chunksmith worker thread. Releasing a ticket calls
    // removeTicketWithRadius, and the server thread is the only thread allowed to touch a chunk ticket
    // (mod_support #16). It corrupted the fastutil ticket graph on a live server and killed it via
    // ArrayIndexOutOfBoundsException in Long2ByteOpenHashMap.rehash plus a 60-second tick. Nothing
    // replaces it: the frontier is bounded by pregenSettleMaxHeld and released by tick().

    /** How many windows are live. Test-visible, because a registry that leaks is a ticket leak. */
    public static int liveWindowCount() {
        return LIVE.size();
    }

    /** Removes every registered window without draining it. The server is going away; see Chunksmith#disable. */
    public static void forget() {
        LIVE.clear();
    }
}
