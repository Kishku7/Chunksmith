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

package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.util.Reflection;

/**
 * Single, cached server-platform detection - resolved once at class load, most-specific first.
 * Folia is a fork of Paper, which is a fork of Spigot, which is a fork of Bukkit, so the
 * membership is nested. Use this everywhere instead of re-probing at each call site, and always
 * test the more specific platform first (e.g. Folia before Paper) for behaviour that differs
 * between them - otherwise Folia silently falls into the Paper path, which is how the
 * getAverageTickTime() "Not on any region" crash slipped through.
 */
public enum Platform {
    FOLIA,
    PAPER,
    SPIGOT,
    BUKKIT;

    private static final Platform CURRENT = detect();

    private static Platform detect() {
        if (Reflection.classExists("io.papermc.paper.threadedregions.RegionizedServer")
                || Reflection.classExists("io.papermc.paper.threadedregions.RegionizedServerInitEvent")) {
            return FOLIA;
        }
        if (Reflection.classExists("com.destroystokyo.paper.PaperConfig")
                || Reflection.classExists("io.papermc.paper.configuration.Configuration")) {
            return PAPER;
        }
        if (Reflection.classExists("org.spigotmc.SpigotConfig")) {
            return SPIGOT;
        }
        return BUKKIT;
    }

    public static Platform current() {
        return CURRENT;
    }

    /** Returns true on Folia exactly (regionized threading; no global tick / off-region restrictions). */
    public static boolean isFolia() {
        return CURRENT == FOLIA;
    }

    /** Returns true when the Paper API surface is available, so Paper AND Folia (Folia is a Paper fork). */
    public static boolean isPaper() {
        return CURRENT == FOLIA || CURRENT == PAPER;
    }

    /** Spigot API surface is available - true on Spigot, Paper, or Folia. */
    public static boolean isSpigot() {
        return CURRENT == FOLIA || CURRENT == PAPER || CURRENT == SPIGOT;
    }
}
