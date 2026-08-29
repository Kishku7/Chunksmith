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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the client's empty-store retry clock.
 *
 * <p>Two failure modes, and the policy has to miss both. Give up, which is what the old client did, and
 * a player who joined before the pregen gets nothing for the entire session. Retry too eagerly and a
 * hundred players parked on a server with no LOD data turn into a permanent trickle of packets nobody asked
 * for. So: short at first (a player who joined seconds before the pregen should barely notice), doubling to
 * a hard ceiling (a server that will NEVER have LOD data costs one small packet every two minutes, forever,
 * and that is all).
 *
 * <p>The clock takes the time as an argument precisely so this can be proven in microseconds rather than
 * asserted by watching a log for four minutes.
 */
public class CsLodRetryTest {

    @Test
    public void nothingIsDueBeforeTheFirstDelay() {
        CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        retry.started(1_000L);

        assertFalse(retry.due(1_000L));
        assertFalse(retry.due(15_999L));
        assertTrue(retry.due(16_000L));
    }

    // 15s, 30s, 60s, then 120s forever.
    @Test
    public void theDelayDoublesToTheCeiling() {
        CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        retry.started(0L);

        assertEquals(15_000L, retry.delayMillis());
        retry.attempted(15_000L);
        assertEquals(30_000L, retry.delayMillis());
        retry.attempted(45_000L);
        assertEquals(60_000L, retry.delayMillis());
        retry.attempted(105_000L);
        assertEquals(120_000L, retry.delayMillis());

        // And it never goes past the ceiling, however long the player sits there.
        for (int i = 0; i < 100; i++) {
            retry.attempted(200_000L + i * 120_000L);
            assertEquals("past the ceiling", 120_000L, retry.delayMillis());
        }
        assertEquals(103, retry.attempts());
    }

    @Test
    public void anAttemptRestartsTheClock() {
        CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        retry.started(0L);

        assertTrue(retry.due(15_000L));
        retry.attempted(15_000L);

        assertFalse("not due yet", retry.due(15_001L));
        assertFalse(retry.due(44_999L));
        assertTrue(retry.due(45_000L));
    }

    @Test
    public void countsAttempts() {
        CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        assertEquals(0, retry.attempts());
        retry.started(0L);
        assertEquals("the join handshake is not a retry", 0, retry.attempts());

        retry.attempted(15_000L);
        retry.attempted(45_000L);
        assertEquals(2, retry.attempts());
    }

    @Test
    public void resetPutsItBackToTheFirstDelay() {
        CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        retry.started(0L);
        retry.attempted(15_000L);
        retry.attempted(45_000L);
        retry.attempted(105_000L);
        assertEquals(120_000L, retry.delayMillis());
        assertEquals(3, retry.attempts());

        retry.reset();

        assertEquals(15_000L, retry.delayMillis());
        assertEquals(0, retry.attempts());

        // And the clock is cleared, not left holding the old deadline: the next join stamps it with
        // started(), and the first retry is a full first-delay away from that, not from the last session.
        retry.started(500_000L);
        assertFalse(retry.due(500_000L));
        assertFalse(retry.due(514_999L));
        assertTrue(retry.due(515_000L));
    }

    @Test
    public void theShippedPolicyIsFifteenSecondsToTwoMinutes() {
        CsLodRetry retry = new CsLodRetry();
        assertEquals(CsLodRetry.FIRST_DELAY_MILLIS, retry.delayMillis());
        assertEquals(15_000L, CsLodRetry.FIRST_DELAY_MILLIS);
        assertEquals(120_000L, CsLodRetry.MAX_DELAY_MILLIS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aBackwardsCeilingIsRefused() {
        new CsLodRetry(60_000L, 15_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aZeroDelayIsRefused() {
        new CsLodRetry(0L, 120_000L);
    }
}
