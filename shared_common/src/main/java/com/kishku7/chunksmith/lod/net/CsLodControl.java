package com.kishku7.chunksmith.lod.net;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The seam between the platform-free half of Chunksmith and the LOD backchannel that only the
 * Minecraft side can see.
 *
 * <p>Three things have to cross this line, and none of them can be reached from here directly:
 * <ul>
 *   <li><b>rebind</b> -- {@code /cs set lodBackchannelPort} has to move the listener NOW, not at the
 *       next restart. An operator whose host will not rent them {@code gamePort + 1} is exactly the
 *       operator who cannot casually restart (mod_support #19).</li>
 *   <li><b>the game port</b> -- so a port that cannot possibly work is refused at the moment it is
 *       typed rather than accepted, written to disk, and discovered to be dead later.</li>
 *   <li><b>a description</b> -- so {@code /cs status} can say what the backchannel is actually
 *       doing.</li>
 * </ul>
 *
 * <p>All three are absent on Bukkit and before a server exists, and each accessor says so rather
 * than inventing an answer. A setting that silently does nothing is the failure this whole issue is
 * about, so "I do not know" has to be representable.
 *
 * <p>Deliberately a static holder: {@code /cs set} reaches the config layer through a chain that
 * carries no Minecraft context at all, and threading one through every setting to serve a single
 * key would cost more than it explains.
 */
public final class CsLodControl {

    /** Rebind the backchannel on the currently configured port. Returns the port now bound, 0 if none. */
    @FunctionalInterface
    public interface Action {
        int rebind();
    }

    private static volatile Action action;
    private static volatile java.util.function.IntSupplier gamePort;
    private static volatile java.util.function.Supplier<String> describe;

    private CsLodControl() {
    }

    /** Called by the Minecraft side once a server is up. */
    public static void register(final Action rebindAction,
                                final java.util.function.IntSupplier gamePortSupplier,
                                final java.util.function.Supplier<String> describeSupplier) {
        action = rebindAction;
        gamePort = gamePortSupplier;
        describe = describeSupplier;
    }

    /** Called when the server stops, so a stale server can never be rebound or described. */
    public static void clear() {
        action = null;
        gamePort = null;
        describe = null;
    }

    /**
     * Move the backchannel to the configured port.
     *
     * @return the port now bound (0 = in-band fallback), or empty when there is nothing to rebind
     */
    public static OptionalInt apply() {
        final Action current = action;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.rebind());
    }

    /**
     * The port the game itself is listening on, when a server is running.
     *
     * <p>Used to refuse it as a backchannel port BEFORE the value is stored. The bind would fail
     * anyway -- the game already holds that port -- but a bind failure happens after the setting has
     * been written and saved, which leaves an operator told "done" while the feature is off and
     * stays off across restarts.
     */
    public static OptionalInt gamePort() {
        final java.util.function.IntSupplier current = gamePort;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.getAsInt());
    }

    /** One line describing the backchannel, for {@code /cs status}. Empty when there is no server. */
    public static Optional<String> describe() {
        final java.util.function.Supplier<String> current = describe;
        return current == null ? Optional.empty() : Optional.ofNullable(current.get());
    }
}
