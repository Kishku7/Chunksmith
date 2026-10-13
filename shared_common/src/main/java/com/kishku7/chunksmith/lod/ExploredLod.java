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

package com.kishku7.chunksmith.lod;

import java.util.concurrent.atomic.AtomicLong;

/**
 * LOD from chunks players explore ({@code lodFromExploredChunks}, on by default since 4.6.0).
 *
 * <p>Until 4.6.0 the only thing that ever wrote the CSLOD store was a pregen: ground a player walked
 * into for the first time had no LOD unless someone pregenerated it, so a server whose players explore
 * past the pregen edge showed nothing out there (mod_support #46). Now each platform hands every
 * fully generated chunk it saves (mod loaders) or unloads (Bukkit/Paper) to {@link #shouldTake}; a chunk
 * the store does not hold yet goes down the same pipeline a pregen uses, and the growth push carries it
 * to connected players.
 *
 * <p>This class holds the platform-neutral half: the rule and the counters {@code /cs lod status} shows.
 */
public final class ExploredLod {

    private static final AtomicLong TAKEN = new AtomicLong();
    private static final AtomicLong HELD = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();

    private ExploredLod() {
    }

    /**
     * The rule, pure so it is testable. Every argument is cheap to know at the save point, and they are
     * checked in this order at the call sites so the common case -- a chunk the store already holds, or
     * one with nothing new to save -- costs a flag test and at most one presence lookup.
     *
     * @param settingOn  {@code lodFromExploredChunks}
     * @param lodOn      LOD generation is on for this server ({@code lodEnabled})
     * @param fullChunk  the chunk is fully generated (a proto chunk at the edge of worldgen has no
     *                   finished surface to take)
     * @param dirty      the save will write something; a clean chunk was already saved, and taken then
     * @param storeHasIt the CSLOD store already holds this chunk (a pregen took it, or we did)
     */
    public static boolean shouldTake(boolean settingOn, boolean lodOn, boolean fullChunk, boolean dirty,
                                     boolean storeHasIt) {
        return settingOn && lodOn && fullChunk && dirty && !storeHasIt;
    }

    public static void noteTaken() {
        TAKEN.incrementAndGet();
    }

    public static void noteHeld() {
        HELD.incrementAndGet();
    }

    /** A take that threw. Never rethrown: a chunk save must not fail because its LOD could not be made. */
    public static void noteFailed() {
        FAILED.incrementAndGet();
    }

    public static long taken() {
        return TAKEN.get();
    }

    /** For {@code /cs lod status}. */
    public static String describe(boolean settingOn) {
        if (!settingOn) {
            return "off (lodFromExploredChunks false)";
        }
        return TAKEN.get() + " chunks added since start, " + HELD.get() + " already in the store"
                + (FAILED.get() == 0 ? "" : ", " + FAILED.get() + " failed");
    }

    /** Server stop. The counters describe one server run. */
    public static void reset() {
        TAKEN.set(0);
        HELD.set(0);
        FAILED.set(0);
    }
}
