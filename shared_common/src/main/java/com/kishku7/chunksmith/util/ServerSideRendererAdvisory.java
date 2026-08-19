package com.kishku7.chunksmith.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Says so when a dedicated server is carrying an LOD renderer it does not need.
 *
 * <p><b>The claim.</b> Chunksmith builds its own LOD data (the CSLOD store) as it pregenerates, and
 * serves that store to the player's client, which injects it into whichever renderer the PLAYER has. The
 * renderer is a CLIENT-side mod. A dedicated server does not render anything, so it does not need one.
 *
 * <p><b>Why say anything at all.</b> Installing Distant Horizons server-side is a reasonable-looking
 * mistake -- it is the mod the feature is "about", and it has a server half, so it looks required. It is
 * not free: on a live server (2026-08-19) a server-side Distant Horizons was running 43 threads, its own
 * world-gen queues, a delayed save cache and a per-dimension update propagator alongside a Chunksmith
 * pregen that was already generating the same terrain. With {@code synchronizeOnLoad} on, it also
 * re-sends LODs the client already has. None of that is a crash, and none of it is a conflict -- which is
 * exactly why nobody notices it. It is duplicated work on the one machine that has none to spare.
 *
 * <p><b>What this is not.</b> It is not a refusal and it is not a {@code breaks} declaration. Distant
 * Horizons is a renderer we FEED, never one we break, and an operator with a reason to run it server-side
 * (serving vanilla DH clients that do not have Chunksmith) is entitled to. So: one line, once, at
 * startup, saying what is installed and what it is costing. Then get out of the way.
 *
 * <p>Deliberately MC-free and loader-free -- it takes a "is this mod present" predicate and gives back a
 * string -- so the rule is unit-testable and identical on every loader.
 */
public final class ServerSideRendererAdvisory {

    /**
     * Renderer mod ids worth mentioning: the ones Chunksmith can actually feed on the client.
     *
     * <p>Same id on every loader that ships them. Anything not on this list is somebody else's mod and
     * none of our business.
     */
    private static final List<String> RENDERER_IDS = List.of("distanthorizons", "voxy");

    private ServerSideRendererAdvisory() {
    }

    /** The renderer ids this class knows how to advise about. */
    public static List<String> rendererIds() {
        return RENDERER_IDS;
    }

    /**
     * The advisory line, or empty when there is nothing to say.
     *
     * @param dedicated  true only on a dedicated server. An integrated server (single player, or a LAN
     *                   world) is running inside a client that DOES need a renderer, so there is never
     *                   anything to advise there -- and saying it would be flatly wrong.
     * @param modPresent asks whether a mod id is installed
     */
    public static Optional<String> message(final boolean dedicated, final Predicate<String> modPresent) {
        if (!dedicated) {
            return Optional.empty();
        }
        final List<String> found = new ArrayList<>();
        for (final String id : RENDERER_IDS) {
            if (modPresent.test(id)) {
                found.add(id);
            }
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        final String names = String.join(" and ", found);
        final String subject = found.size() == 1 ? "is" : "are";
        final String pronoun = found.size() == 1 ? "it" : "them";
        return Optional.of(names + " " + subject + " installed on this DEDICATED SERVER, where Chunksmith"
                + " does not need " + pronoun + ". Chunksmith builds its own LOD data while it"
                + " pregenerates and serves that to each player's client, which injects it into the"
                + " renderer THEY have installed -- an LOD renderer is a client-side mod. Running one"
                + " here costs threads, memory and disk generating a second copy of terrain this server"
                + " is already generating. Removing it is the recommended setup. Keep it only if you"
                + " deliberately serve players who do not have Chunksmith installed.");
    }
}
