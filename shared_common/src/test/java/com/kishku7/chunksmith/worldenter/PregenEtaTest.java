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

package com.kishku7.chunksmith.worldenter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The estimate the player decides on.
 *
 * <p>Driven against a clock the test controls, because the alternative is
 * watching a real pregen for an hour to find out whether one number is right.
 */
public class PregenEtaTest {

    private static PregenEta steady(long chunksPerSecond, int seconds) {
        PregenEta eta = new PregenEta();
        for (int i = 0; i <= seconds; i++) {
            eta.sample(i * 1000L, i * chunksPerSecond);
        }
        return eta;
    }

    // ---------------------------------------------------------------- refusing to guess

    @Test
    public void sayNothingBeforeThereIsAnythingToSay() {
        PregenEta eta = new PregenEta();
        assertEquals(-1, eta.secondsRemaining(0, 1000));
        assertEquals("estimating...", eta.describe(0, 1000));

        eta.sample(0L, 0L);   // one sample is still not a rate
        assertEquals(-1, eta.secondsRemaining(0, 1000));
    }

    @Test
    public void aStalledRunHasNoEstimateRatherThanAnInfiniteOne() {
        PregenEta eta = new PregenEta();
        for (int i = 0; i < 6; i++) {
            eta.sample(i * 1000L, 500L);   // time passing, nothing generated
        }
        assertEquals(-1, eta.secondsRemaining(500, 200000));
        assertEquals("estimating...", eta.describe(500, 200000));
    }

    @Test
    public void anUnknownTotalHasNoEstimate() {
        assertEquals(-1, steady(50, 10).secondsRemaining(500, 0));
    }

    @Test
    public void finishedIsNotAnEstimate() {
        assertEquals(-1, steady(50, 10).secondsRemaining(1000, 1000));
    }

    // ---------------------------------------------------------------- the arithmetic

    @Test
    public void aSteadyRateGivesTheObviousAnswer() {
        PregenEta eta = steady(100, 10);            // 100 chunks/sec
        assertEquals(100.0, eta.ratePerSecond(), 0.001);
        // 10,000 left at 100/sec = 100 seconds
        assertEquals(100L, eta.secondsRemaining(1000, 11000));
    }

    @Test
    public void theWindowFollowsTheRateDownRatherThanAveragingItAway() {
        // The case this class exists for: a burst over already-generated ground, then real terrain.
        PregenEta eta = new PregenEta();
        long done = 0;
        for (int i = 0; i < 10; i++) {          // 2000 chunks/sec -- skipping existing chunks
            done += 2000;
            eta.sample(i * 1000L, done);
        }
        double fast = eta.ratePerSecond();
        for (int i = 10; i < 20; i++) {         // then 40/sec -- generating for real
            done += 40;
            eta.sample(i * 1000L, done);
        }
        double slow = eta.ratePerSecond();
        assertTrue("the burst must not be carried forever (fast=" + fast + " slow=" + slow + ")",
                slow < fast / 10);
        assertEquals("and the rate should reflect what is happening NOW", 40.0, slow, 1.0);
    }

    @Test
    public void aClockThatDoesNotMoveIsIgnored() {
        PregenEta eta = new PregenEta();
        eta.sample(1000L, 100L);
        eta.sample(1000L, 200L);   // same instant -- would be an infinite rate
        eta.sample(1000L, 300L);
        assertEquals(0.0, eta.ratePerSecond(), 0.001);
    }

    @Test
    public void resetForgetsAnOldRun() {
        PregenEta eta = steady(100, 10);
        assertTrue(eta.ratePerSecond() > 0);
        eta.reset();
        assertEquals(0.0, eta.ratePerSecond(), 0.001);
    }

    // ---------------------------------------------------------------- the bar

    @Test
    public void theBarIsClampedAtBothEnds() {
        assertEquals(0.0, PregenEta.fraction(0, 100), 0.0001);
        assertEquals(0.5, PregenEta.fraction(50, 100), 0.0001);
        assertEquals(1.0, PregenEta.fraction(100, 100), 0.0001);
        assertEquals("over-complete must not overflow the bar",
                1.0, PregenEta.fraction(150, 100), 0.0001);
        assertEquals("an unknown total must not divide by zero",
                0.0, PregenEta.fraction(50, 0), 0.0001);
    }

    // ---------------------------------------------------------------- the words

    @Test
    public void timeReadsTheWayAPersonWouldSayIt() {
        assertEquals("less than a minute", PregenEta.humanTime(0));
        assertEquals("less than a minute", PregenEta.humanTime(59));
        assertEquals("1 minute", PregenEta.humanTime(60));
        assertEquals("2 minutes", PregenEta.humanTime(120));
        assertEquals("59 minutes", PregenEta.humanTime(59 * 60));
        assertEquals("1 hour", PregenEta.humanTime(3600));
        assertEquals("1 hour 1 minute", PregenEta.humanTime(3660));
        assertEquals("2 hours 5 minutes", PregenEta.humanTime(2 * 3600 + 5 * 60));
        assertEquals("unknown", PregenEta.humanTime(-1));
    }

    @Test
    public void theDefaultRadiusReadsAsHoursNotAsASecondsCountdown() {
        // 205,887 chunks at ~45/sec is the realistic default-radius case. Whatever it says, it must
        // be a phrase the player can act on, not a stopwatch implying precision we do not have.
        PregenEta eta = steady(45, 10);
        String said = eta.describe(0, 205887);
        assertTrue(said, said.startsWith("about "));
        assertTrue(said, said.endsWith(" remaining"));
        assertTrue(said, said.contains("hour"));
    }
}
