/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Says so, at ERROR level, when a dedicated server is carrying an LOD renderer it must not have.
 *
 * <p>Chunksmith builds its own LOD data as it pregenerates and serves it to the player's client,
 * which injects it into whichever renderer the player has. An LOD renderer is a client-side mod
 * and a dedicated server does not render anything, so one installed here is pure cost.
 *
 * <p>Installing Distant Horizons server-side is a reasonable-looking mistake -- it is the mod the
 * feature is "about" and it has a server half -- and it is not free. On a live server a
 * server-side Distant Horizons ran 43 threads, its own world-gen queues, a delayed save cache and
 * a per-dimension update propagator alongside a Chunksmith pregen already generating the same
 * terrain, and with {@code synchronizeOnLoad} on it re-sent LODs the client already had. It is
 * also what turns a {@code /cslod dhpush} at the console into pages of DH's own "No DH level
 * provided" warnings, which is how it reached us: mod_support #27, where nothing was wrong with
 * the mod and removing DH from the server was the entire fix.
 *
 * <p><b>Why ERROR and not WARN.</b> A warn is one grey line in a startup log nobody reads to the
 * end, and this one was missed by the operator it was written for. ERROR renders red in the
 * server console and survives being scrolled past. Chunksmith still starts and still works: this
 * reports a misconfiguration of the server, not a failure of the mod, and we do not refuse to run
 * or declare {@code breaks} over it -- Distant Horizons is a renderer we FEED, and an operator
 * deliberately serving vanilla DH clients is entitled to run it. Loud, once, at startup, then out
 * of the way.
 */
public final class ServerSideRendererError {

    /**
     * Renderer mod ids worth reporting: the ones Chunksmith can actually feed on the client. Same
     * id on every loader that ships them; anything not on this list is somebody else's mod.
     */
    private static final List<String> RENDERER_IDS = List.of("distanthorizons", "voxy");

    /** Drawn above and below the block so it reads as one thing in a busy startup log. */
    private static final String RULE = "*".repeat(78);

    private ServerSideRendererError() {
    }

    public static List<String> rendererIds() {
        return RENDERER_IDS;
    }

    /**
     * Returns the banner to log, one entry per line, or an empty list when there is nothing to
     * say. Lines are returned separately rather than joined on newlines because a logger colours
     * and prefixes per event: one call per line keeps every line red and stamped, where an
     * embedded newline would leave all but the first bare.
     *
     * @param dedicated  true only on a dedicated server. An integrated server runs inside a client
     *                   that DOES need a renderer, so saying this there would be flatly wrong
     * @param modPresent asks whether a mod id is installed
     */
    public static List<String> lines(boolean dedicated, Predicate<String> modPresent) {
        if (!dedicated) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String id : RENDERER_IDS) {
            if (modPresent.test(id)) {
                found.add(id);
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        String names = String.join(" and ", found);
        String verb = found.size() == 1 ? "is" : "are";
        String pronoun = found.size() == 1 ? "it" : "them";

        List<String> out = new ArrayList<>();
        out.add(RULE);
        out.add(names + " " + verb + " installed on this DEDICATED SERVER. REMOVE "
                + pronoun.toUpperCase(Locale.ROOT) + ".");
        out.add("An LOD renderer is a CLIENT mod. This server does not render anything.");
        out.add("Chunksmith builds its own LOD data while it pregenerates and serves that to each");
        out.add("player's client, which injects it into the renderer THEY have installed.");
        out.add("Running one here costs threads, memory and disk generating a second copy of the");
        out.add("terrain this server is already generating, and it is what fills the console with");
        out.add("DH's \"No DH level provided\" warnings during /cslod dhpush.");
        out.add("Removing it is the recommended setup. Keep it only if you deliberately serve");
        out.add("players who do not have Chunksmith installed.");
        out.add("Chunksmith itself is running normally; this is a server misconfiguration.");
        out.add(RULE);
        return out;
    }
}
