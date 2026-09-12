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

import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which advertised regions a client will actually keep, given a disk budget (mod_support #30).
 *
 * <p><b>Why a client needs a say at all.</b> How much a client writes has always been the SERVER's
 * decision: it advertises an index, the client fetches all of it. The server's {@code lodIndexBudgetMb}
 * bounds that, but it defaults to 2 GB per dimension and an operator may raise it to a terabyte or
 * switch it off. Nothing on this side could ever decline.
 *
 * <p><b>Why declining is not simply "do not fetch".</b> The sync poll folds what we hold over the index
 * the server last sent and compares (count, aggregate) against the server's own fold. A client that
 * quietly skipped regions would mismatch on EVERY poll, pull a full index every time, and never settle
 * -- a disk problem traded for an endless-download problem. So a decline has to be representable in
 * that comparison, which is what {@link #foldOut} is for: the aggregate is an XOR of per-region tokens
 * and XOR is its own inverse, so declined regions can be folded back OUT of the server's number
 * exactly. Not approximated -- exactly.
 *
 * <p>Order is the priority. The server builds its index nearest-first, so keeping a prefix keeps the
 * terrain closest to the player, which is the terrain a renderer draws first.
 */
public final class CsLodDiskBudget {

    /** What a budget decided: what to fetch, and what to tell the sync poll we are not holding. */
    public record Split(List<CsLodMessages.RegionEntry> keep,
                        List<CsLodMessages.RegionEntry> declined) {

        public boolean cappedAnything() {
            return !declined.isEmpty();
        }

        /** Bytes the declined regions would have cost. For saying WHY in a log line. */
        public long declinedBytes() {
            long total = 0L;
            for (CsLodMessages.RegionEntry entry : declined) {
                total += Math.max(0L, entry.sizeBytes());
            }
            return total;
        }
    }

    private CsLodDiskBudget() {
    }

    /**
     * Splits an index into what fits and what does not.
     *
     * @param regions      the server's index, nearest-first
     * @param onDiskBytes  what this dimension's store already occupies; regions we already hold are not
     *                     charged twice, so this is the floor the budget is measured from
     * @param budgetBytes  the cap, or {@code <= 0} for no cap, which is the default and is what every
     *                     client did before 4.0.0
     */
    public static Split apply(final List<CsLodMessages.RegionEntry> regions,
                              final long onDiskBytes,
                              final long budgetBytes) {
        if (regions == null || regions.isEmpty()) {
            return new Split(List.of(), List.of());
        }
        if (budgetBytes <= 0L) {
            return new Split(List.copyOf(regions), List.of());
        }

        List<CsLodMessages.RegionEntry> keep = new ArrayList<>();
        List<CsLodMessages.RegionEntry> declined = new ArrayList<>();

        // Start from what is already there rather than from zero. Otherwise the first index after a
        // restart would "fit" a store that is already over budget and the cap would never bind.
        long used = Math.max(0L, onDiskBytes);
        for (CsLodMessages.RegionEntry entry : regions) {
            long cost = Math.max(0L, entry.sizeBytes());
            if (used + cost <= budgetBytes) {
                keep.add(entry);
                used += cost;
            } else {
                declined.add(entry);
            }
        }
        return new Split(keep, declined);
    }

    /**
     * Removes one region's contribution from a summary aggregate.
     *
     * <p>Exactly {@link CsLodSummary#fold}: XOR is its own inverse, so folding a token in and folding
     * it out are the same operation. Named differently because the INTENT at the call site is the
     * whole point, and a future reader should not have to rediscover the symmetry to trust it.
     */
    public static long foldOut(final long aggregate, final CsLodMessages.RegionEntry entry) {
        return CsLodSummary.fold(aggregate, entry.regionX(), entry.regionZ(), entry.hash());
    }
}
