package com.kishku7.chunksmith.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The state machine behind the auto-pause policy: pause when the server cannot
 * sustain a run, resume when it can, and never undo a decision a human made.
 *
 * <p>Both directions need patience, and the tests care most about the
 * impatient failures: pausing on a blip stops a healthy run for an autosave,
 * and resuming on a blip walks straight back into the wall that caused the
 * pause.
 */
public class AutoPauseTest {

    private static final long T0 = 1_000_000L;
    private static final long GRACE = 120_000L;

    @Before
    public void reset() {
        AutoPause.clear();
        AutoPause.configure(true, GRACE);
    }

    @After
    public void tearDown() {
        AutoPause.clear();
    }

    @Test
    public void tickTroubleCountsWithNoGateOfOursClosed() {
        // The 3.7.0 flaw: keyed on our gates alone, auto-pause sat idle through twelve "Can't keep
        // up" warnings because the chunk gate was off and the heap was under its threshold.
        AutoPause.noteStruggling(true, T0);
        assertTrue("struggling with no gate of ours closed", AutoPause.shouldPause(T0 + GRACE));
    }

    @Test
    public void aBlipDoesNotPause() {
        AutoPause.noteStruggling(true, T0);
        assertFalse(AutoPause.shouldPause(T0 + GRACE - 1));
        // Recovered before the grace expired: the clock must start over, not carry on.
        AutoPause.noteStruggling(false, T0 + GRACE - 1);
        AutoPause.noteStruggling(true, T0 + GRACE);
        assertFalse("a brief stall must not pause", AutoPause.shouldPause(T0 + GRACE + 1));
    }

    @Test
    public void aSustainedStallPauses() {
        AutoPause.noteStruggling(true, T0);
        AutoPause.noteStruggling(true, T0 + 60_000L);
        assertTrue(AutoPause.shouldPause(T0 + GRACE));
        assertEquals(120L, AutoPause.strugglingSeconds(T0 + GRACE));
    }

    @Test
    public void aBriefRecoveryDoesNotResume() {
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        assertFalse(AutoPause.shouldResume(T0 + GRACE - 1));
        AutoPause.noteHealthy(false, T0 + GRACE - 1);
        AutoPause.noteHealthy(true, T0 + GRACE);
        assertFalse("must not resume on a blip",
                AutoPause.shouldResume(T0 + GRACE + 1));
    }

    @Test
    public void sustainedRecoveryResumes() {
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        assertTrue(AutoPause.shouldResume(T0 + GRACE));
        assertEquals("minecraft:overworld", AutoPause.pausedWorld());
    }

    @Test
    public void onlyOurPauseResumes() {
        AutoPause.noteHealthy(true, T0);
        assertFalse("nothing to resume",
                AutoPause.shouldResume(T0 + GRACE * 10));
    }

    @Test
    public void aHumanPauseOutranksUsBothWays() {
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        AutoPause.clear();
        assertFalse(AutoPause.isAutoPaused());
        assertFalse("a deliberate pause must stay paused", AutoPause.shouldResume(T0 + GRACE * 10));
    }

    @Test
    public void disabledMeansNothingFires() {
        AutoPause.configure(false, GRACE);
        AutoPause.noteStruggling(true, T0);
        assertFalse(AutoPause.shouldPause(T0 + GRACE * 10));
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        assertFalse(AutoPause.shouldResume(T0 + GRACE * 10));
    }

    @Test
    public void noDoublePause() {
        AutoPause.noteStruggling(true, T0);
        assertTrue(AutoPause.shouldPause(T0 + GRACE));
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteStruggling(true, T0 + GRACE);
        assertFalse("already paused",
                AutoPause.shouldPause(T0 + GRACE * 3));
    }

    @Test
    public void resumingResetsTheState() {
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        assertTrue(AutoPause.shouldResume(T0 + GRACE));
        AutoPause.clearAutoPaused();
        assertFalse(AutoPause.isAutoPaused());
        assertFalse(AutoPause.shouldResume(T0 + GRACE * 2));
    }

    @Test
    public void describeHasNoPercent() {
        AutoPause.markAutoPaused("minecraft:overworld");
        assertFalse(AutoPause.describe().contains("%"));
        assertTrue(String.format(AutoPause.describe()).length() > 0);
    }
}
