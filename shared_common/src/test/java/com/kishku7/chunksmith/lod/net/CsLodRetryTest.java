package com.kishku7.chunksmith.lod.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the client's empty-store retry clock.
 *
 * <p>Two failure modes, and the policy has to miss both. Give up -- which is what the old client did -- and
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
    public void nothingIsDueBeforeTheFirstDelayHasElapsed() {
        final CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        retry.started(1_000L);

        assertFalse(retry.due(1_000L));
        assertFalse(retry.due(15_999L));
        assertTrue(retry.due(16_000L));
    }

    /** The shipped curve: 15s, 30s, 60s, then 120s forever. */
    @Test
    public void theDelayDoublesUpToTheCeilingAndStaysThere() {
        final CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
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
            assertEquals("the ceiling is a ceiling", 120_000L, retry.delayMillis());
        }
        assertEquals(103, retry.attempts());
    }

    /** Each attempt restarts the wait -- a retry may never fire twice on one deadline. */
    @Test
    public void anAttemptRestartsTheClock() {
        final CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        retry.started(0L);

        assertTrue(retry.due(15_000L));
        retry.attempted(15_000L);

        assertFalse("just asked -- next one is 30s away, not now", retry.due(15_001L));
        assertFalse(retry.due(44_999L));
        assertTrue(retry.due(45_000L));
    }

    @Test
    public void attemptsAreCountedSoTheLogCanSaySoInPlainWords() {
        final CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
        assertEquals(0, retry.attempts());
        retry.started(0L);
        assertEquals("the join handshake is not a retry", 0, retry.attempts());

        retry.attempted(15_000L);
        retry.attempted(45_000L);
        assertEquals(2, retry.attempts());
    }

    /** The store turned up (or we disconnected): back to square one, so the next session starts fresh. */
    @Test
    public void resetPutsItBackToTheFirstDelay() {
        final CsLodRetry retry = new CsLodRetry(15_000L, 120_000L);
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
        // started(), and the first retry is a full first-delay away from THAT, not from the last session.
        retry.started(500_000L);
        assertFalse(retry.due(500_000L));
        assertFalse(retry.due(514_999L));
        assertTrue(retry.due(515_000L));
    }

    @Test
    public void theShippedPolicyIsFifteenSecondsToTwoMinutes() {
        final CsLodRetry retry = new CsLodRetry();
        assertEquals(CsLodRetry.FIRST_DELAY_MILLIS, retry.delayMillis());
        assertEquals(15_000L, CsLodRetry.FIRST_DELAY_MILLIS);
        assertEquals(120_000L, CsLodRetry.MAX_DELAY_MILLIS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCeilingBelowTheFirstDelayIsNonsenseAndIsRefused() {
        new CsLodRetry(60_000L, 15_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aZeroDelayWouldBeAPacketStormAndIsRefused() {
        new CsLodRetry(0L, 120_000L);
    }
}
