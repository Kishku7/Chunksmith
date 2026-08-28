package com.kishku7.chunksmith.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Says so when a dedicated server is carrying an LOD renderer it does not need.
 *
 * <p>Chunksmith builds its own LOD data as it pregenerates and serves it to the player's client, which
 * injects it into whichever renderer the player has. An LOD renderer is a client-side mod, and a
 * dedicated server does not render anything.
 *
 * <p>Installing Distant Horizons server-side is a reasonable-looking mistake (it is the mod the
 * feature is "about" and it has a server half) and it is not free. On a live server a server-side
 * Distant Horizons ran 43 threads, its own world-gen queues, a delayed save cache and a per-dimension
 * update propagator alongside a Chunksmith pregen already generating the same terrain, and with
 * {@code synchronizeOnLoad} on it re-sent LODs the client already had.
 *
 * <p>Still not a refusal and not a {@code breaks} declaration: Distant Horizons is a renderer we feed,
 * and an operator serving vanilla DH clients is entitled to run it. One line, once, at startup.
 */
public final class ServerSideRendererAdvisory {

    /**
     * Renderer mod ids worth mentioning: the ones Chunksmith can actually feed on the client. Same id on
     * every loader that ships them; anything not on this list is somebody else's mod.
     */
    private static final List<String> RENDERER_IDS = List.of("distanthorizons", "voxy");

    private ServerSideRendererAdvisory() {
    }

    public static List<String> rendererIds() {
        return RENDERER_IDS;
    }

    /**
     * The advisory line, or empty when there is nothing to say.
     *
     * @param dedicated  true only on a dedicated server. An integrated server runs inside a client that
     *                   DOES need a renderer, so saying this there would be flatly wrong
     * @param modPresent asks whether a mod id is installed
     */
    public static Optional<String> message(boolean dedicated, Predicate<String> modPresent) {
        if (!dedicated) {
            return Optional.empty();
        }
        final List<String> found = new ArrayList<>();
        for (String id : RENDERER_IDS) {
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
                + " renderer THEY have installed; an LOD renderer is a client-side mod. Running one"
                + " here costs threads, memory and disk generating a second copy of terrain this server"
                + " is already generating. Removing it is the recommended setup. Keep it only if you"
                + " deliberately serve players who do not have Chunksmith installed.");
    }
}
