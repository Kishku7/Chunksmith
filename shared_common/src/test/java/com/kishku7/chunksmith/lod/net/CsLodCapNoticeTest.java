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

import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The throttle and the wording behind the "index was capped" warning.
 *
 * <p>The clock is passed in rather than read, so the quiet period is asserted
 * rather than waited out; a test that slept ten minutes to prove a ten-minute
 * window is a test nobody runs.
 */
public class CsLodCapNoticeTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static CsLodIndexScan.Result result(int kept, int found) {
        return new CsLodIndexScan.Result(
                java.util.Collections.nCopies(kept, new CsLodMessages.RegionEntry(0, 0, 0L, 0L)),
                found, 0L);
    }

    // ---------------------------------------------------------------- the throttle

    @Test
    public void theFirstCapIsAlwaysReported() {
        assertTrue(new CsLodCapNotice().shouldWarn(ALICE, 1_000L));
    }

    @Test
    public void aSecondCapInsideTheQuietPeriodIsNot() {
        CsLodCapNotice notice = new CsLodCapNotice();
        assertTrue(notice.shouldWarn(ALICE, 1_000L));
        // This is the case that produced six lines in seventy-three seconds: a player on an elytra
        // asking again roughly every nine seconds.
        assertFalse(notice.shouldWarn(ALICE, 10_000L));
        assertFalse(notice.shouldWarn(ALICE, 20_000L));
        assertFalse(notice.shouldWarn(ALICE, 1_000L + CsLodCapNotice.QUIET_MILLIS - 1L));
    }

    @Test
    public void theQuietPeriodEnds() {
        CsLodCapNotice notice = new CsLodCapNotice();
        assertTrue(notice.shouldWarn(ALICE, 1_000L));
        assertTrue(notice.shouldWarn(ALICE, 1_000L + CsLodCapNotice.QUIET_MILLIS));
    }

    @Test
    public void oneQuietPlayerDoesNotSilenceAnother() {
        CsLodCapNotice notice = new CsLodCapNotice();
        assertTrue(notice.shouldWarn(ALICE, 1_000L));
        assertTrue(notice.shouldWarn(BOB, 1_000L));
        assertFalse(notice.shouldWarn(ALICE, 2_000L));
    }

    @Test
    public void forgettingAPlayerReportsTheirNextCap() {
        CsLodCapNotice notice = new CsLodCapNotice();
        assertTrue(notice.shouldWarn(ALICE, 1_000L));
        assertFalse(notice.shouldWarn(ALICE, 2_000L));
        // Someone who logs out and back in is a new session, and their operator should hear about it.
        notice.forget(ALICE);
        assertTrue(notice.shouldWarn(ALICE, 3_000L));
    }

    @Test
    public void clearingForgetsEveryone() {
        CsLodCapNotice notice = new CsLodCapNotice();
        notice.shouldWarn(ALICE, 1_000L);
        notice.shouldWarn(BOB, 1_000L);
        notice.clear();
        assertTrue(notice.shouldWarn(ALICE, 2_000L));
        assertTrue(notice.shouldWarn(BOB, 2_000L));
    }

    // ---------------------------------------------------------------- what it says

    @Test
    public void theRegionCapSaysTravellingHelps() {
        String said = CsLodCapNotice.explain(
                result(CsLodIndexScan.MAX_REGIONS, CsLodIndexScan.MAX_REGIONS + 1), 0L);
        assertTrue(said, said.contains("travelling does"));
        assertTrue(said, said.contains(String.valueOf(CsLodIndexScan.MAX_REGIONS)));
    }

    @Test
    public void aBudgetCapDoesNotSayTravellingHelps() {
        // The whole reason this class exists: the old line promised travel would fix a cap that
        // trims the same way wherever the player stands.
        String said = CsLodCapNotice.explain(result(10, 20), 512L);
        assertFalse(said, said.contains("travelling"));
        assertTrue(said, said.contains("lodIndexBudgetMb"));
        assertTrue(said, said.contains("512"));
    }

    @Test
    public void theTwoCausesDoNotShareWording() {
        assertNotEquals(
                CsLodCapNotice.explain(result(10, 20), 512L),
                CsLodCapNotice.explain(
                        result(CsLodIndexScan.MAX_REGIONS, CsLodIndexScan.MAX_REGIONS + 1), 0L));
    }

    @Test
    public void anUncappedResultIsNeitherCause() {
        CsLodIndexScan.Result full = new CsLodIndexScan.Result(List.of(), 0, 0L);
        assertFalse(full.capped());
        assertFalse(full.cappedByBudget());
        assertFalse(full.cappedByRegionCount());
        assertEquals(0, full.found());
    }
}
