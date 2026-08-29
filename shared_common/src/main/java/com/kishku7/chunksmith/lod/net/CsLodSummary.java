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

package com.kishku7.chunksmith.lod.net;

/**
 * A whole region index folds to two numbers, (count, aggregate), and comparing those is what a periodic
 * sync costs: 22 bytes out, 34 bytes back when nothing has changed.
 *
 * <p>The sync exists for the player standing still, who otherwise sees nothing new until they relog,
 * because the travel refresh only fires on movement. The client asks for a summary, compares it against
 * the same two numbers computed over what it holds, and only when they differ does it pay for a full
 * index.
 *
 * <p>The fold has to be order-independent, since the server enumerates from {@code Files.list} and the
 * client from the last index it was given: identical sets, different order. Hence XOR rather than a
 * checksum. XOR of raw hashes would be a bad aggregate, though: equal tokens cancel to zero and swapping
 * two regions' coordinates is invisible. Each region is therefore bound to its own coordinates and
 * avalanched, {@code token(x, z, hash)}, and those are XORed, with the count carried alongside as a
 * second check.
 */
public final class CsLodSummary {

    private CsLodSummary() {
    }

    /** A folded index: how many regions, and one number standing in for all of their freshness tokens. */
    public record Snapshot(int count, long aggregate) {

        public static final Snapshot EMPTY = new Snapshot(0, 0L);
    }

    /** Folds one region into a running aggregate; start from 0. */
    public static long fold(long aggregate, int regionX, int regionZ, long hash) {
        return aggregate ^ token(regionX, regionZ, hash);
    }

    /**
     * Returns the per-region contribution, with (x, z, hash) avalanched into 64 bits so every field
     * participates in every output bit. Change any of the three and this number is unrelated to what it was.
     */
    public static long token(int regionX, int regionZ, long hash) {
        long packed = ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
        return mix(mix(packed) ^ (hash * 0x9E3779B97F4A7C15L));
    }

    /** SplitMix64's finalizer, the same avalanche {@link CsLodRegionHash} uses, for the same reason. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
