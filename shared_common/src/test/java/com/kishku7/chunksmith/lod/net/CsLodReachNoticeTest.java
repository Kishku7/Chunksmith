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

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The "nothing ever reached the backchannel" diagnosis, and the two ways it could lie.
 *
 * <p>mod_support #24, #26 and #31 were all the same cause -- a backchannel port that was never
 * reachable -- and all three were missed despite the port being printed at startup. This notice
 * fires at the moment of failure instead, so what matters is that it fires ONLY on that exact
 * signature and does not nag.
 */
public class CsLodReachNoticeTest {

    /**
     * Zero served AND zero rejected is the whole signal. A rejection proves a request arrived, so
     * any non-zero count rules this diagnosis out -- the problem is then tokens, permissions or the
     * store, and saying "your port is closed" would send the operator the wrong way entirely.
     */
    @Test
    public void onlyZeroServedAndZeroRejectedMeansNothingArrived() {
        assertTrue(CsLodReachNotice.unreached(true, 0L, 0L));
        assertFalse("a served file proves the port is reachable",
                CsLodReachNotice.unreached(true, 1L, 0L));
        assertFalse("a rejection also proves a request arrived",
                CsLodReachNotice.unreached(true, 0L, 1L));
        assertFalse(CsLodReachNotice.unreached(true, 5L, 5L));
    }

    /**
     * A backchannel that is not running has its own message. Firing this one there would tell an
     * operator to open a port for a server that is not listening on it.
     */
    @Test
    public void aBackchannelThatIsNotRunningIsNotThisProblem() {
        assertFalse(CsLodReachNotice.unreached(false, 0L, 0L));
    }

    @Test
    public void oneLinePerPlayerPerQuietPeriod() {
        CsLodReachNotice notice = new CsLodReachNotice();
        UUID player = UUID.randomUUID();
        assertTrue(notice.shouldWarn(player, 0L));
        assertFalse("an in-band fetch a second later must not print again",
                notice.shouldWarn(player, 1_000L));
        assertFalse(notice.shouldWarn(player, CsLodReachNotice.QUIET_MILLIS - 1L));
        assertTrue("and an operator who looks later still finds a recent one",
                notice.shouldWarn(player, CsLodReachNotice.QUIET_MILLIS));
    }

    /** Two players on one broken server each deserve the line -- the throttle is not global. */
    @Test
    public void playersAreThrottledIndependently() {
        CsLodReachNotice notice = new CsLodReachNotice();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(notice.shouldWarn(a, 0L));
        assertTrue(notice.shouldWarn(b, 0L));
        assertFalse(notice.shouldWarn(a, 100L));
    }

    @Test
    public void forgettingAPlayerReportsTheirNextFetch() {
        CsLodReachNotice notice = new CsLodReachNotice();
        UUID player = UUID.randomUUID();
        assertTrue(notice.shouldWarn(player, 0L));
        assertFalse(notice.shouldWarn(player, 100L));
        notice.forget(player);
        assertTrue("a reconnecting player is a fresh diagnosis", notice.shouldWarn(player, 200L));
    }

    /** The message has to carry the port and the two fixes, or it is just an alarm. */
    @Test
    public void theExplanationNamesThePortAndWhatToDo() {
        for (boolean inBand : new boolean[] {true, false}) {
            String message = CsLodReachNotice.explain(25566, inBand);
            assertTrue(message.contains("25566"));
            assertTrue(message.contains("lodBackchannelHost"));
            assertTrue(message.contains("lodBackchannelPort"));
        }
    }

    /**
     * The CONSEQUENCE differs by platform and the message must not lie about it.
     *
     * <p>The mod falls back in-band, so an unreachable port is slow. The Bukkit plugin has no
     * fallback at all, so the same fault means no LOD ever arrives. Telling a Paper operator their
     * players are "falling back to a slower channel" would have them wait for something that is
     * never coming -- which is the whole reason this takes a flag instead of being one string.
     */
    @Test
    public void theConsequenceIsPlatformAccurate() {
        String mod = CsLodReachNotice.explain(25566, true);
        assertTrue("the mod is slow, not dead", mod.contains("in-band"));
        assertTrue(mod.contains("only about speed"));

        String plugin = CsLodReachNotice.explain(25566, false);
        assertTrue("the plugin has no fallback and must say so",
                plugin.contains("NO in-band fallback"));
        assertTrue(plugin.contains("no LOD"));
        assertFalse("must NOT promise a slow fallback the plugin does not have",
                plugin.contains("only about speed"));
    }

    /**
     * The FIRST exchange with a client is never evidence -- it has not had time to come back.
     * Without this the plugin would warn on every healthy server the moment a client connected.
     */
    @Test
    public void theFirstAnswerIsNotEvidence() {
        CsLodReachNotice notice = new CsLodReachNotice();
        UUID player = UUID.randomUUID();
        assertFalse("first time: no prior chance to fetch", notice.hasBeenAnsweredBefore(player));
        assertTrue("second time: it has had its chance", notice.hasBeenAnsweredBefore(player));
        assertTrue(notice.hasBeenAnsweredBefore(player));
    }

    @Test
    public void forgettingAPlayerAlsoForgetsThatTheyWereAnswered() {
        CsLodReachNotice notice = new CsLodReachNotice();
        UUID player = UUID.randomUUID();
        notice.hasBeenAnsweredBefore(player);
        notice.forget(player);
        assertFalse("a reconnecting player starts over", notice.hasBeenAnsweredBefore(player));
    }
}
