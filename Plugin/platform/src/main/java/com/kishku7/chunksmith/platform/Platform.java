package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.util.Reflection;

/**
 * Single, cached server-platform detection - resolved ONCE at class load, most-specific
 * first. Folia is a fork of Paper, which is a fork of Spigot, which is a fork of Bukkit, so
 * the membership is nested. Use this everywhere instead of re-probing at each call site, and
 * ALWAYS test the more specific platform first (e.g. Folia before Paper) for behaviour that
 * differs between them - otherwise Folia silently falls into the Paper path, which is how the
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

    /** Exactly Folia (regionized threading; no global tick / off-region restrictions). */
    public static boolean isFolia() {
        return CURRENT == FOLIA;
    }

    /** Paper API surface is available - true on Paper AND Folia (Folia is a Paper fork). */
    public static boolean isPaper() {
        return CURRENT == FOLIA || CURRENT == PAPER;
    }

    /** Spigot API surface is available - true on Spigot, Paper, or Folia. */
    public static boolean isSpigot() {
        return CURRENT == FOLIA || CURRENT == PAPER || CURRENT == SPIGOT;
    }
}
