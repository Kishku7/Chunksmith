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
    /** The shipped default for throttleMaxHeapPercent; the resume point derives from it. */
    private static final long HEAP_THRESHOLD = 85L;
    /** The controller has nothing left to try: the precondition for an auto-pause. */
    private static final boolean AT_FLOOR = true;

    @Before
    public void reset() {
        AutoPause.clear();
        TickBudget.reset();
        AutoPause.configure(true, GRACE, HEAP_THRESHOLD);
    }

    @After
    public void tearDown() {
        AutoPause.clear();
    }

    @Test
    public void tickTroubleCountsWithNoGateOfOursClosed() {
        // The 3.7.0 flaw: keyed on our gates alone, auto-pause sat idle through twelve "Can't keep
        // up" warnings because the chunk gate was off and the heap was under its threshold.
        AutoPause.noteStruggling(true, true, T0);
        assertTrue("struggling with no gate of ours closed", AutoPause.shouldPause(T0 + GRACE, AT_FLOOR));
    }

    @Test
    public void aBlipDoesNotPause() {
        AutoPause.noteStruggling(true, true, T0);
        assertFalse(AutoPause.shouldPause(T0 + GRACE - 1, AT_FLOOR));
        // Recovered before the grace expired: the clock must start over, not carry on.
        AutoPause.noteStruggling(false, true, T0 + GRACE - 1);
        AutoPause.noteStruggling(true, true, T0 + GRACE);
        assertFalse("a brief stall must not pause", AutoPause.shouldPause(T0 + GRACE + 1, AT_FLOOR));
    }

    @Test
    public void aSustainedStallPauses() {
        AutoPause.noteStruggling(true, true, T0);
        AutoPause.noteStruggling(true, true, T0 + 60_000L);
        assertTrue(AutoPause.shouldPause(T0 + GRACE, AT_FLOOR));
        assertEquals(120L, AutoPause.strugglingSeconds(T0 + GRACE));
    }

    /**
     * Still "a blip is not a recovery", but measured on the RESUME grace, which is seconds and no
     * longer the pause-side grace. The assertion used to be written against GRACE because both
     * directions shared one knob; that sharing is the thing that made every pause last two
     * minutes, so the contract here changed deliberately rather than the test drifting.
     */
    @Test
    public void aBriefRecoveryDoesNotResume() {
        long resumeGrace = AutoPause.resumeGraceMillis();
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        assertFalse(AutoPause.shouldResume(T0 + resumeGrace - 1));
        AutoPause.noteHealthy(false, T0 + resumeGrace - 1);
        AutoPause.noteHealthy(true, T0 + resumeGrace);
        assertFalse("must not resume on a blip",
                AutoPause.shouldResume(T0 + resumeGrace + 1));
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
        AutoPause.configure(false, GRACE, HEAP_THRESHOLD);
        AutoPause.noteStruggling(true, true, T0);
        assertFalse(AutoPause.shouldPause(T0 + GRACE * 10, AT_FLOOR));
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        assertFalse(AutoPause.shouldResume(T0 + GRACE * 10));
    }

    @Test
    public void noDoublePause() {
        AutoPause.noteStruggling(true, true, T0);
        assertTrue(AutoPause.shouldPause(T0 + GRACE, AT_FLOOR));
        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteStruggling(true, true, T0 + GRACE);
        assertFalse("already paused",
                AutoPause.shouldPause(T0 + GRACE * 3, AT_FLOOR));
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

    // ---- healthyNow: the resume side must use the PAUSE side's references (mod_support #33) ----

    /**
     * THE REGRESSION. The old resume test was {@code mspt <= 55 && heap < 70}, both hard-coded.
     * A machine whose own idle tick cost is 80 ms can never satisfy the first, so a run that
     * auto-paused on it could never come back -- the reporter on #33 sat at 5.08% indefinitely.
     * Resume must be judged against what the machine can actually do, not an absolute.
     */
    @Test
    public void aSlowMachineCanStillBecomeHealthy() {
        assertTrue("80ms on a machine with no measured target must not block resume for ever",
                AutoPause.healthyNow(80.0D, 40.0D));
    }

    /** The heap arm tracks the CONFIGURED threshold, not a hard-coded 70. */
    @Test
    public void heapArmFollowsTheConfiguredThreshold() {
        // default 85 -> the dispatch gate reopens at 85 - 15 = 70, and so does resume
        assertTrue("69pct is under the resume point for an 85pct threshold",
                AutoPause.healthyNow(10.0D, 69.0D));
        assertFalse("71pct is over it", AutoPause.healthyNow(10.0D, 71.0D));

        // a tighter threshold moves the resume point with it, floored at 50 as HeapPressure floors it
        AutoPause.configure(true, GRACE, 60L);
        assertFalse("60pct threshold floors the resume point at 50pct, so 55pct is still too high",
                AutoPause.healthyNow(10.0D, 55.0D));
        assertTrue(AutoPause.healthyNow(10.0D, 49.0D));
    }

    /** A heap reading the platform cannot supply is not evidence of health. */
    @Test
    public void unreadableHeapIsNotHealthy() {
        assertFalse(AutoPause.healthyNow(10.0D, -1.0D));
    }

    /**
     * The tick arm compares against the measured target plus the throttle's own dead-band --
     * the same numbers the pause side uses, so the two can no longer disagree.
     */
    @Test
    public void tickArmComparesAgainstTheMeasuredTarget() {
        // Teach TickBudget a baseline: one idle sample with a fresh player count is taken as-is.
        TickBudget.configure(25L, 20L, 150L);
        TickBudget.sample(60.0D, false, 0);
        double target = TickBudget.effectiveTarget();
        assertTrue("a baseline should now exist", target > 0.0D);

        assertTrue("at the target is healthy",
                AutoPause.healthyNow(target, 40.0D));
        assertTrue("inside the dead-band is healthy",
                AutoPause.healthyNow(target + TickBudget.MSPT_BAND, 40.0D));
        assertFalse("clear of the dead-band is not",
                AutoPause.healthyNow(target + TickBudget.MSPT_BAND + 1.0D, 40.0D));
    }

    /** describeHealth exists to name the arm that is holding a run down; it must not carry a raw %. */
    @Test
    public void describeHealthIsSafeToFormat() {
        String line = AutoPause.describeHealth(40.0D, 62.0D);
        assertFalse(line.contains("%"));
        assertTrue(line.contains("heap="));
        assertTrue(line.contains("mspt="));
    }

    // ---- pause is a LAST RESORT, and a pause is a short event (the owner's two rules) ----

    /**
     * "Pausing should be a last resort, and only after a good hard try, even if the working
     * numbers are outside of the ideal -- some work is better than no work."
     *
     * <p>A run with room left to narrow must narrow instead of stopping. This is the test the
     * old code could not have passed: it fired on elapsed time alone, so a client with a
     * perfectly usable width of 16 still available to it stopped dead at 5.08% (mod_support #33).
     */
    @Test
    public void aRunWithRoomLeftToNarrowMustNotPause() {
        AutoPause.noteStruggling(true, true, T0);
        assertFalse("there is still width to give up -- narrow, do not stop",
                AutoPause.shouldPause(T0 + GRACE * 10, false));
        assertTrue("at the hard minimum, a pause is finally the honest answer",
                AutoPause.shouldPause(T0 + GRACE, true));
    }

    /**
     * "If a pause has to last more than 10 seconds, some number is wrong."
     *
     * <p>Both directions used to share one grace knob, so a 120s pause-side patience also made
     * every recovery wait 120s: a 10-second pause was impossible whatever the machine did.
     */
    @Test
    public void aRecoveredMachineResumesInSecondsNotMinutes() {
        assertTrue("resume grace must be seconds", AutoPause.resumeGraceMillis() <= 10_000L);
        assertTrue("and must not be the pause-side grace",
                AutoPause.resumeGraceMillis() < GRACE);

        AutoPause.markAutoPaused("minecraft:overworld");
        AutoPause.noteHealthy(true, T0);
        assertFalse("not instantly -- a blip is not a recovery",
                AutoPause.shouldResume(T0 + AutoPause.resumeGraceMillis() - 1));
        assertTrue("but well inside 10 seconds",
                AutoPause.shouldResume(T0 + 10_000L));
    }

    /** A pause-side grace SHORTER than the cap still governs the resume side; it is a cap, not a value. */
    @Test
    public void resumeGraceIsACapNotAFixedValue() {
        AutoPause.configure(true, 2_000L, HEAP_THRESHOLD);
        assertEquals(2_000L, AutoPause.resumeGraceMillis());
    }

    /** A resumed run comes back BELOW the width that failed, so recovery is not a sawtooth. */
    @Test
    public void aResumedRunStartsNarrowerThanTheWidthThatFailed() {
        assertEquals("no recommendation until something has failed", 0,
                AutoPause.recommendedStartWidth());
        AutoPause.noteWidthFailed(16);
        assertEquals(8, AutoPause.recommendedStartWidth());
        AutoPause.noteWidthFailed(1);
        assertEquals("never below a width that still generates", 1,
                AutoPause.recommendedStartWidth());
    }

    /**
     * The width that failed is carried so a resumed run cannot burst back past it.
     *
     * <p>mod_support #36: the run was resumed NARROW while the slow-start threshold it uses to
     * decide how fast it may widen was reset to the configured ceiling, so the narrow start
     * survived exactly one sample.
     */
    @Test
    public void theFailedWidthIsRememberedForTheResumedRun() {
        assertEquals("nothing has failed yet", 0, AutoPause.failedWidth());
        AutoPause.noteWidthFailed(64);
        assertEquals(64, AutoPause.failedWidth());
        AutoPause.clear();
        assertEquals("a new run inherits no failure", 0, AutoPause.failedWidth());
    }

    /**
     * The resume grace doubles on every consecutive pause, and is bounded.
     *
     * <p>A fixed five seconds is the right answer to a passing autosave and the wrong answer to a
     * machine that has already failed at this three times: it produces the fixed-period
     * pause/resume/fail oscillation a reporter described as "it pauses too often".
     */
    @Test
    public void theResumeGraceEscalatesOnRepeatedPauses() {
        AutoPause.configure(true, GRACE, HEAP_THRESHOLD);
        long first = AutoPause.resumeGraceMillis();
        assertEquals("the first resume is as eager as it ever was", 5_000L, first);

        AutoPause.markAutoPaused("world");
        assertEquals("one pause is not yet a pattern", first, AutoPause.resumeGraceMillis());

        AutoPause.markAutoPaused("world");
        assertEquals(first * 2, AutoPause.resumeGraceMillis());
        AutoPause.markAutoPaused("world");
        assertEquals(first * 4, AutoPause.resumeGraceMillis());

        for (int i = 0; i < 20; i++) {
            AutoPause.markAutoPaused("world");
        }
        assertTrue("bounded -- a pause that outlasts its own patience is a stop",
                AutoPause.resumeGraceMillis() <= 60_000L);
        assertTrue("and never longer than the pause-side grace",
                AutoPause.resumeGraceMillis() <= AutoPause.graceMillis());
    }

    /** Coming back is not success. STAYING back is, and only that clears the escalation. */
    @Test
    public void onlySustainedHealthClearsTheEscalation() {
        AutoPause.configure(true, GRACE, HEAP_THRESHOLD);
        AutoPause.markAutoPaused("world");
        AutoPause.markAutoPaused("world");
        assertEquals(2, AutoPause.consecutivePauses());

        AutoPause.clearAutoPaused();
        assertEquals("a resume on its own proves nothing", 2, AutoPause.consecutivePauses());
        assertEquals(10_000L, AutoPause.resumeGraceMillis());

        AutoPause.noteSustainedHealth();
        assertEquals(0, AutoPause.consecutivePauses());
        assertEquals("back to eager once the machine has held a width",
                5_000L, AutoPause.resumeGraceMillis());
    }

    /**
     * The below-floor clock is its own number, not the resume grace doubled.
     *
     * <p>It used to be read off {@code resumeGraceMillis() * 2}. Once the resume grace started
     * escalating that would have made the controller slower to NARROW every time it got more
     * patient about resuming -- two unrelated decisions welded together.
     */
    @Test
    public void theBelowFloorClockDoesNotMoveWhenTheResumeGraceEscalates() {
        AutoPause.configure(true, GRACE, HEAP_THRESHOLD);
        long before = AutoPause.belowFloorGraceMillis();
        assertEquals(10_000L, before);
        for (int i = 0; i < 5; i++) {
            AutoPause.markAutoPaused("world");
        }
        assertTrue("the resume grace did escalate", AutoPause.resumeGraceMillis() > 5_000L);
        assertEquals("the below-floor clock did not move", before, AutoPause.belowFloorGraceMillis());
    }

    /** A new run must not inherit the last one's recommendation. */
    @Test
    public void clearForgetsTheRecommendation() {
        AutoPause.noteWidthFailed(32);
        AutoPause.clear();
        assertEquals(0, AutoPause.recommendedStartWidth());
    }


    // ---- the countdown is announced only when it can come true (mod_support #37) ----

    @Test
    public void aStruggleAboveTheMinimumAnnouncesNothing() {
        AutoPause.configure(true, GRACE, HEAP_THRESHOLD);
        AutoPause.noteStruggling(true, false, T0);
        assertFalse("a gate hold cannot auto-pause, so no countdown is promised",
                AutoPause.countdownAnnounced());
        assertFalse(AutoPause.shouldPause(T0 + GRACE, false));
    }

    @Test
    public void reachingTheMinimumAnnouncesOnceAndRecoveryCancels() {
        AutoPause.configure(true, GRACE, HEAP_THRESHOLD);
        AutoPause.noteStruggling(true, false, T0);
        AutoPause.noteStruggling(true, true, T0 + 5_000L);
        assertTrue("now it can pause, so say when", AutoPause.countdownAnnounced());
        AutoPause.noteStruggling(true, true, T0 + 6_000L);
        assertTrue(AutoPause.countdownAnnounced());
        AutoPause.noteStruggling(false, true, T0 + 7_000L);
        assertFalse("recovery cancels it", AutoPause.countdownAnnounced());
        assertEquals(0L, AutoPause.strugglingSeconds(T0 + 7_000L));
    }

    @Test
    public void noCountdownWhenThePauseIsAlreadyDue() {
        AutoPause.configure(true, GRACE, HEAP_THRESHOLD);
        AutoPause.noteStruggling(true, false, T0);
        AutoPause.noteStruggling(true, true, T0 + GRACE);
        assertFalse("the pause line is next; a zero-second countdown says nothing",
                AutoPause.countdownAnnounced());
        assertTrue(AutoPause.shouldPause(T0 + GRACE, true));
    }

    @Test
    public void disabledNeverAnnounces() {
        AutoPause.configure(false, GRACE, HEAP_THRESHOLD);
        AutoPause.noteStruggling(true, true, T0);
        assertFalse(AutoPause.countdownAnnounced());
    }
}
