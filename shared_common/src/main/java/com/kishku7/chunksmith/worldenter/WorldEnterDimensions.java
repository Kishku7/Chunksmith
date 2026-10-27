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

package com.kishku7.chunksmith.worldenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which dimensions the world-enter pregen covers (mod_support #52, {@code worldEnterPregenDimensions}).
 *
 * <p>A comma-separated list of {@code overworld}, {@code nether} and {@code end}, or {@code all}.
 * Default {@code overworld}, which is what the feature did before. The dimensions run one after another
 * behind the same screen, always in the order overworld, nether, end, whatever order they were typed in.
 */
public final class WorldEnterDimensions {

    public static final String DEFAULT = "overworld";

    public static final String OVERWORLD_ID = "minecraft:overworld";
    public static final String NETHER_ID = "minecraft:the_nether";
    public static final String END_ID = "minecraft:the_end";

    private WorldEnterDimensions() {
    }

    /**
     * The dimension ids a spec names, in run order, or null when any part of it is not one of the three.
     * Whitespace and case are ignored; a repeated name counts once; an empty spec is not valid.
     */
    public static List<String> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        boolean overworld = false;
        boolean nether = false;
        boolean end = false;
        for (String part : spec.split(",")) {
            switch (part.trim().toLowerCase(Locale.ROOT)) {
                case "overworld" -> overworld = true;
                case "nether" -> nether = true;
                case "end" -> end = true;
                case "all" -> {
                    overworld = true;
                    nether = true;
                    end = true;
                }
                default -> {
                    return null;
                }
            }
        }
        List<String> ids = new ArrayList<>(3);
        if (overworld) {
            ids.add(OVERWORLD_ID);
        }
        if (nether) {
            ids.add(NETHER_ID);
        }
        if (end) {
            ids.add(END_ID);
        }
        return ids;
    }

    /** The canonical spec for a parsed list: what the config file holds. {@code all} when all three. */
    public static String spec(List<String> ids) {
        if (ids.size() == 3) {
            return "all";
        }
        List<String> names = new ArrayList<>(ids.size());
        for (String id : ids) {
            names.add(shortName(id));
        }
        return String.join(",", names);
    }

    /** {@code overworld}, {@code nether} or {@code end}. */
    public static String shortName(String id) {
        if (NETHER_ID.equals(id)) {
            return "nether";
        }
        if (END_ID.equals(id)) {
            return "end";
        }
        return "overworld";
    }

    /** What the progress screen calls a dimension. */
    public static String displayName(String id) {
        if (NETHER_ID.equals(id)) {
            return "the Nether";
        }
        if (END_ID.equals(id)) {
            return "the End";
        }
        return "the Overworld";
    }

    /**
     * The file a dimension's completion record lives in. The overworld keeps the original name, so a world
     * finished before 4.8.0 is still recognised; the others get their own.
     */
    public static String recordFileName(String id) {
        if (NETHER_ID.equals(id)) {
            return "worldenter-done-nether.json";
        }
        if (END_ID.equals(id)) {
            return "worldenter-done-end.json";
        }
        return WorldEnterDone.FILE_NAME;
    }
}
