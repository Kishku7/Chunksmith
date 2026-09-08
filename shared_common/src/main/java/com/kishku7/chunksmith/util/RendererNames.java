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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LOD renderers Chunksmith knows about: the id a loader answers to, and the name a human calls
 * the thing they installed.
 *
 * <p>Detection is always BY ID -- that is the only name the loader has -- but nothing an operator
 * READS should say {@code distanthorizons} at them when the mod on their disk is called Distant
 * Horizons. Both halves live here, in one file, because they had already drifted: the startup
 * banner and the LOD auto-enable line printed different vocabularies two lines apart in the same
 * log, and the server-side error knew about two renderers while the LOD detector knew about three.
 *
 * <p>The display names are the ones the projects give themselves, read from their own manifests
 * ({@code voxy}'s {@code fabric.mod.json} says {@code "name": "Voxy"}), not invented here.
 */
public final class RendererNames {

    /** Distant Horizons. Same id on every loader that ships it. */
    public static final String DISTANT_HORIZONS = "distanthorizons";

    /** Upstream Voxy, and five of the six known forks -- all of which keep the upstream id. */
    public static final String VOXY = "voxy";

    /** neo-voxy (meansabine), the one fork that renamed itself. */
    public static final String NEOVOXY = "neovoxy";

    /** Ordered so every message that walks this list reads the same way every time. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put(DISTANT_HORIZONS, "Distant Horizons");
        NAMES.put(VOXY, "Voxy");
        NAMES.put(NEOVOXY, "neo-voxy");
    }

    private RendererNames() {
    }

    /** Every renderer id, in display order. */
    public static List<String> ids() {
        return List.copyOf(NAMES.keySet());
    }

    /**
     * The human name for a renderer id.
     *
     * @param id a mod id
     * @return the name its author gives it, or the id unchanged when we do not know it -- an
     *         unknown renderer is better reported by its id than not reported at all
     */
    public static String display(String id) {
        String name = NAMES.get(id);
        return name != null ? name : id;
    }

    /** Every renderer's display name, in order -- for "looked for ..." style messages. */
    public static List<String> displayNames() {
        return new ArrayList<>(NAMES.values());
    }
}
