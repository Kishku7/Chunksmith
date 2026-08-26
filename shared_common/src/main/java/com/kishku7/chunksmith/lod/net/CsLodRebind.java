package com.kishku7.chunksmith.lod.net;

import java.util.OptionalInt;

/**
 * The seam that lets a config change move the running backchannel.
 *
 * <p>Setting {@code lodBackchannelPort} has to take effect NOW, not at the next restart -- an operator
 * whose host will not give them {@code gamePort + 1} is exactly the operator who cannot casually restart
 * (mod_support #19). But the code that owns the listener lives on the Minecraft side: it needs the running
 * server to find the world directory, and the player list to re-issue tokens. This package does not, and
 * must not, know any of that.
 *
 * <p>So the Minecraft side registers what to do and this side calls it. When nothing is registered -- on
 * Bukkit, or before the server has started -- {@link #apply()} reports that plainly rather than pretending
 * the port moved. A setting that silently does nothing is the failure mode this whole issue is about.
 *
 * <p>Deliberately a static holder rather than something injected: {@code /cs set} reaches the config layer
 * through a chain that carries no Minecraft context at all, and threading one through every setting to
 * serve a single key would cost more than it explains.
 */
public final class CsLodRebind {

    /** Rebind the backchannel on the currently configured port. Returns the port now bound, 0 if none. */
    @FunctionalInterface
    public interface Action {
        int rebind();
    }

    private static volatile Action action;

    private CsLodRebind() {
    }

    /** Called by the Minecraft side once a server is up. */
    public static void register(final Action rebind) {
        action = rebind;
    }

    /** Called when the server stops, so a stale server can never be rebound. */
    public static void clear() {
        action = null;
    }

    /**
     * Move the backchannel to the configured port.
     *
     * @return the port now bound (0 = in-band fallback), or empty when there is nothing to rebind --
     *         no server running, or a platform that has no backchannel
     */
    public static OptionalInt apply() {
        final Action current = action;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.rebind());
    }
}
