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

import java.util.Locale;

/**
 * Where the world-enter pregen is centred: {@code origin}, {@code spawn}, or an explicit
 * {@code x,z}.
 *
 * <p><b>Why this is a setting and not simply "use spawn".</b> The centre was a hard-coded
 * {@code (0, 0)} until 4.3.0, so every world that has ever run this feature ran it around origin.
 * Switching the default to spawn would move the ground under those worlds -- and somebody who
 * deliberately pregenerated around origin, which is where a vanilla world puts you anyway, should
 * not have that changed for them by an update. So {@code origin} stays the default and
 * {@code spawn} is opt-in. Reported as mod_support #34: mods that relocate world spawn made the
 * hard-coded origin pregenerate terrain the player never sees.
 *
 * <p><b>{@code spawn} is resolved late, not stored.</b> It is read when the pregen starts, because
 * the spawn point can move between loads; what gets WRITTEN to the completion record is the place
 * that was actually generated (see {@code WorldEnterDone}), since a finished run is a fact about
 * ground, not about intent.
 *
 * <p>Parsing is deliberately strict. An unrecognised value is rejected rather than quietly treated
 * as origin: silently falling back would answer "done" to {@code /cs set} and change nothing, which
 * is the failure mode this codebase has paid for more than once.
 */
public final class WorldEnterCenter {

    /** The default, and what every pre-4.3.0 world already did. */
    public static final String ORIGIN = "origin";

    /** Follow the world's spawn point, resolved when the pregen starts. */
    public static final String SPAWN = "spawn";

    private static final WorldEnterCenter AT_ORIGIN = new WorldEnterCenter(false, 0.0, 0.0);
    private static final WorldEnterCenter AT_SPAWN = new WorldEnterCenter(true, 0.0, 0.0);

    private final boolean followsSpawn;
    private final double x;
    private final double z;

    private WorldEnterCenter(boolean followsSpawn, double x, double z) {
        this.followsSpawn = followsSpawn;
        this.x = x;
        this.z = z;
    }

    public static WorldEnterCenter origin() {
        return AT_ORIGIN;
    }

    public static WorldEnterCenter spawn() {
        return AT_SPAWN;
    }

    public static WorldEnterCenter at(double x, double z) {
        return new WorldEnterCenter(false, x, z);
    }

    /**
     * Parses a configured or typed spec, or returns null if it is not one.
     *
     * <p>Accepts {@code origin}, {@code spawn}, and {@code x,z} with or without spaces around the
     * comma. Null for anything else, including a lone number -- half a coordinate pair is a typo,
     * and guessing which axis was meant would be worse than refusing.
     */
    public static WorldEnterCenter parse(String spec) {
        if (spec == null) {
            return null;
        }
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (ORIGIN.equals(lower)) {
            return origin();
        }
        if (SPAWN.equals(lower)) {
            return spawn();
        }
        int comma = trimmed.indexOf(',');
        if (comma <= 0 || comma == trimmed.length() - 1) {
            return null;
        }
        if (trimmed.indexOf(',', comma + 1) >= 0) {
            return null;
        }
        try {
            double px = Double.parseDouble(trimmed.substring(0, comma).trim());
            double pz = Double.parseDouble(trimmed.substring(comma + 1).trim());
            if (!isFinite(px) || !isFinite(pz)) {
                return null;
            }
            return at(px, pz);
        } catch (NumberFormatException notCoordinates) {
            return null;
        }
    }

    /** True when the centre has to be read off the world rather than taken from here. */
    public boolean followsSpawn() {
        return followsSpawn;
    }

    /** The centre, for the forms that carry one. Meaningless when {@link #followsSpawn()}. */
    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    /** The canonical spec, which is what a config read-back and {@code /cs set} should show. */
    public String spec() {
        if (followsSpawn) {
            return SPAWN;
        }
        if (x == 0.0 && z == 0.0) {
            return ORIGIN;
        }
        return trim(x) + "," + trim(z);
    }

    @Override
    public String toString() {
        return spec();
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** Whole numbers read back as {@code 512,-64} rather than {@code 512.0,-64.0}. */
    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0E15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
