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

package com.kishku7.chunksmith.lod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * This one string is the whole address of a CSLOD record. A region coordinate on its own is meaningless:
 * region (0,0) exists in the overworld, in the Nether and in the End, and they are three different places.
 * Every read, every write and every injection is scoped by this key ({@code chunksmith/lod/&lt;server&gt;/
 * &lt;dimension&gt;/r.x.z.cslod}), and getting it wrong does not fail, it succeeds against the wrong world.
 *
 * <p>3.1.0-beta-2's client took the dimension from the first entry of the server's hello list and never
 * looked at it again, so a player who walked through a Nether portal kept pulling the overworld's records
 * and the injector pushed them into the level the player was now in. Grass and oceans in the Nether sky,
 * and every log line reporting success. Ask the level, every tick; never remember an answer across a
 * dimension change.
 *
 * <p>The value matches the server's {@code LodSupport.dimensionKey}: the dimension's resource id with
 * {@code :} and {@code /} replaced by {@code _}, e.g. {@code minecraft_overworld},
 * {@code minecraft_the_nether}. That is the directory name the server writes, the name it puts in the
 * region index, and the name the client stores under.
 */
public final class CsLodDimension {

    private CsLodDimension() {
    }

    /**
     * Returns the key for the level the player is in right now, or {@code ""} when no level is loaded (during
     * a dimension change there is a window where there is no level at all; callers must treat "" as "ask me
     * again next tick", never as a dimension).
     *
     * @return the current level key, or {@code ""} when there is no level
     */
    public static String current() {
        Level level = Minecraft.getInstance().level;
        return level == null ? "" : of(level);
    }

    public static String of(Level level) {
        //[[[cog
        // import cog, compat
        // cog.outl("final String id = level.dimension().%s().toString();"
        //          % compat.dimension_identifier_call(mcver))
        //]]]
        //[[[end]]]
        return id.replace(':', '_').replace('/', '_');
    }
}
