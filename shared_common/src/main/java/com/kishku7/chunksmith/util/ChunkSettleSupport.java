package com.kishku7.chunksmith.util;

/**
 * The one place that says whether a pregen should hold its chunks open, and for how long.
 *
 * <p>{@link ChunkSettleWindow} is deliberately MC-free and knows nothing about configuration; the
 * per-loader world adapters that own the chunk tickets know about Minecraft but have no handle on our
 * config. This is the seam between them: the generation task -- which has the config, and which is the
 * only thing that ever makes settling relevant -- states the policy here when a run starts, and each
 * world adapter asks for a window when it needs one.
 *
 * <p>Static because a chunk ticket is server-thread state and there is exactly one server per process.
 * Volatile because the task is constructed off the main thread while the adapters read from it on the
 * server thread.
 */
public final class ChunkSettleSupport {

    private static volatile boolean enabled;
    private static volatile long delayTicks;
    private static volatile long maxHeld;

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
        return enabled ? new ChunkSettleWindow(delayTicks, maxHeld) : null;
    }
}
