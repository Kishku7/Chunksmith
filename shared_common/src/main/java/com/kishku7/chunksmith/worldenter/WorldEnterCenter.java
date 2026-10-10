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
 * Where the world-enter pregen is centred: {@code auto}, {@code origin}, {@code spawn}, or an
 * explicit {@code x,z}.
 *
 * <p><b>The default is {@code auto} from 4.5.0: spawn for a world that has never finished a
 * world-enter pregen, and the centre it finished around for one that has.</b> The centre was a
 * hard-coded {@code (0, 0)} until 4.3.0, and {@code origin} stayed the default after it became a
 * setting (mod_support #34) so that an update could not move the ground under worlds already
 * generated. That kept the trap #34 reported: a mod that relocates spawn (Village Spawn Point, in
 * mod_support #45) sends the pregen thousands of blocks from where the player is, and it looks
 * broken. Plain {@code spawn} as the default would not have been safe: a config file is only
 * written when it is created or changed, so one made before 4.3.0 and never touched has no value,
 * would read the new default, and would send every world it had already finished around origin
 * back behind the freeze screen for the crescent the shifted disc does not cover. {@code auto}
 * keeps those worlds exactly where they were, because a finished world carries its centre in its
 * completion record ({@code WorldEnterDone}), and gives every new world the place the player
 * actually spawns. An explicit {@code origin}, {@code spawn} or {@code x,z} means what it says.
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

    /** The world's (0, 0); what every pre-4.3.0 world did, and the default until 4.5.0. */
    public static final String ORIGIN = "origin";

    /** Follow the world's spawn point, resolved when the pregen starts. */
    public static final String SPAWN = "spawn";

    /** Spawn for a world that has never finished a world-enter pregen; its own centre if it has. */
    public static final String AUTO = "auto";

    /** What an install with no value gets. See the class comment. */
    public static final String DEFAULT = AUTO;

    private static final WorldEnterCenter AT_ORIGIN = new WorldEnterCenter(false, false, 0.0, 0.0);
    private static final WorldEnterCenter AT_SPAWN = new WorldEnterCenter(true, false, 0.0, 0.0);
    private static final WorldEnterCenter AUTOMATIC = new WorldEnterCenter(true, true, 0.0, 0.0);

    private final boolean followsSpawn;
    private final boolean auto;
    private final double x;
    private final double z;

    private WorldEnterCenter(boolean followsSpawn, boolean auto, double x, double z) {
        this.followsSpawn = followsSpawn;
        this.auto = auto;
        this.x = x;
        this.z = z;
    }

    public static WorldEnterCenter origin() {
        return AT_ORIGIN;
    }

    public static WorldEnterCenter spawn() {
        return AT_SPAWN;
    }

    public static WorldEnterCenter auto() {
        return AUTOMATIC;
    }

    public static WorldEnterCenter at(double x, double z) {
        return new WorldEnterCenter(false, false, x, z);
    }

    /**
     * True for {@code auto}: the caller must first ask whether the world already finished a
     * world-enter pregen, and use that run's centre if so. Otherwise it is {@code spawn}.
     */
    public boolean isAuto() {
        return auto;
    }

    /**
     * Parses a configured or typed spec, or returns null if it is not one.
     *
     * <p>Accepts {@code auto}, {@code origin}, {@code spawn}, and {@code x,z} with or without spaces around the
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
        if (AUTO.equals(lower)) {
            return auto();
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
        if (auto) {
            return AUTO;
        }
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
