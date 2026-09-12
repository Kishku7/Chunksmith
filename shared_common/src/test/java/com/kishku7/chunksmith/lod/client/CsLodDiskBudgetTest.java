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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CsLodDiskBudgetTest {

    private static CsLodMessages.RegionEntry region(int x, int z, long size) {
        return new CsLodMessages.RegionEntry(x, z, 0x1234L * (x + 1) + z, size);
    }

    private static List<CsLodMessages.RegionEntry> index(int count, long each) {
        List<CsLodMessages.RegionEntry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(region(i, 0, each));
        }
        return out;
    }

    @Test
    public void noBudgetKeepsEverythingWhichIsWhatEveryClientDidBeforeFourPointZero() {
        List<CsLodMessages.RegionEntry> all = index(50, 1_000_000L);
        for (long budget : new long[] {0L, -1L}) {
            CsLodDiskBudget.Split split = CsLodDiskBudget.apply(all, 900_000_000L, budget);
            assertEquals(50, split.keep().size());
            assertFalse(split.cappedAnything());
        }
    }

    @Test
    public void aBudgetKeepsThePrefixBecauseTheIndexIsNearestFirst() {
        CsLodDiskBudget.Split split = CsLodDiskBudget.apply(index(10, 100L), 0L, 350L);

        assertEquals(3, split.keep().size());
        assertEquals(7, split.declined().size());
        // The ones kept must be the FIRST three, not any three that happen to fit.
        assertEquals(0, split.keep().get(0).regionX());
        assertEquals(1, split.keep().get(1).regionX());
        assertEquals(2, split.keep().get(2).regionX());
    }

    @Test
    public void whatIsAlreadyOnDiskCountsAgainstTheBudget() {
        // Without this the cap would never bind after a restart: a store already over budget would
        // start each session measuring from zero.
        // 350 used + one 100 region = 450, under 500. The next would be 550, over.
        CsLodDiskBudget.Split split = CsLodDiskBudget.apply(index(5, 100L), 350L, 500L);

        assertEquals(1, split.keep().size());
        assertEquals(4, split.declined().size());
    }

    @Test
    public void aStoreAlreadyOverBudgetDeclinesEverythingRatherThanGrowing() {
        CsLodDiskBudget.Split split = CsLodDiskBudget.apply(index(5, 100L), 10_000L, 500L);

        assertTrue(split.keep().isEmpty());
        assertEquals(5, split.declined().size());
        assertEquals(500L, split.declinedBytes());
    }

    @Test
    public void anEmptyIndexIsNotAnError() {
        CsLodDiskBudget.Split split = CsLodDiskBudget.apply(List.of(), 0L, 100L);
        assertTrue(split.keep().isEmpty());
        assertFalse(split.cappedAnything());
    }

    /**
     * The load-bearing one. If this does not hold, a capped client mismatches the server on every sync
     * poll, pulls a full index each time, and never settles -- the disk problem becomes a download
     * problem, which is worse.
     */
    @Test
    public void foldingDeclinedRegionsOutOfTheServerAggregateLandsExactlyOnOurs() {
        List<CsLodMessages.RegionEntry> all = index(10, 100L);

        long serverAggregate = 0L;
        for (CsLodMessages.RegionEntry entry : all) {
            serverAggregate = CsLodSummary.fold(serverAggregate, entry.regionX(), entry.regionZ(), entry.hash());
        }

        CsLodDiskBudget.Split split = CsLodDiskBudget.apply(all, 0L, 350L);

        long ours = 0L;
        for (CsLodMessages.RegionEntry entry : split.keep()) {
            ours = CsLodSummary.fold(ours, entry.regionX(), entry.regionZ(), entry.hash());
        }

        long corrected = serverAggregate;
        for (CsLodMessages.RegionEntry entry : split.declined()) {
            corrected = CsLodDiskBudget.foldOut(corrected, entry);
        }

        assertEquals("a capped client must be able to agree with the server", ours, corrected);
        assertEquals(split.keep().size(), all.size() - split.declined().size());
    }

    @Test
    public void foldingOutIsOrderIndependent() {
        // XOR commutes, and the two sides enumerate differently -- the server from Files.list, the
        // client from the index. Order dependence here would be a heisenbug on somebody else's disk.
        List<CsLodMessages.RegionEntry> declined = index(6, 100L);

        long forwards = 0L;
        for (CsLodMessages.RegionEntry entry : declined) {
            forwards = CsLodDiskBudget.foldOut(forwards, entry);
        }
        long backwards = 0L;
        for (int i = declined.size() - 1; i >= 0; i--) {
            backwards = CsLodDiskBudget.foldOut(backwards, declined.get(i));
        }

        assertEquals(forwards, backwards);
    }

    @Test
    public void aRegionLargerThanTheWholeBudgetIsDeclinedRatherThanWedging() {
        List<CsLodMessages.RegionEntry> all = List.of(region(0, 0, 10_000L), region(1, 0, 10L));
        CsLodDiskBudget.Split split = CsLodDiskBudget.apply(all, 0L, 100L);

        // The oversized one is skipped and the small one behind it still lands. Stopping at the first
        // region that does not fit would strand everything behind it for no gain.
        assertEquals(1, split.keep().size());
        assertEquals(1, split.keep().get(0).regionX());
        assertEquals(1, split.declined().size());
    }

    @Test
    public void negativeSizesOffTheWireAreTreatedAsFree() {
        // Sizes come off an untrusted wire. A negative must not be able to credit the budget upward.
        List<CsLodMessages.RegionEntry> all = List.of(region(0, 0, -5_000L), region(1, 0, 100L));
        CsLodDiskBudget.Split split = CsLodDiskBudget.apply(all, 0L, 100L);

        assertEquals(2, split.keep().size());
        assertEquals(0L, split.declinedBytes());
    }
}
