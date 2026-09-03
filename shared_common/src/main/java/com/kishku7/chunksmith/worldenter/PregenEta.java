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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * How much longer the world-enter pregen has, in words a player can act on.
 *
 * <p>The number this produces is the one thing standing between a player and
 * force-quitting a game they think has hung. A radius of 4096 is around 205,000
 * chunks and can run over an hour; "please wait" with no figure is not enough
 * information to decide whether to wait.
 *
 * <p><b>Why a rolling window and not an average since the start.</b> The rate is
 * not steady. A pregen crossing ground that is already generated skips it
 * without loading -- thousands of chunks a second -- and then hits fresh terrain
 * and drops to tens. An average taken over the whole run carries that early
 * burst forever and reads far too optimistic for the rest of the run; a rate
 * measured over the last few samples tracks what is happening now. It is
 * jumpier, and that is the correct trade: an estimate that moves is honest about
 * a rate that moves.
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested against a
 * clock the test controls, rather than by watching a real pregen for an hour.
 */
public final class PregenEta {

    /**
     * How many samples the rate is measured over.
     *
     * <p>Eight, at roughly one a second, is a few seconds of history: long
     * enough that one slow tick does not throw the estimate, short enough to
     * follow a real change in rate within a few seconds.
     */
    public static final int WINDOW = 8;

    /** Below this many chunks per second, treat the run as stalled rather than slow. */
    private static final double STALLED_RATE = 0.05;

    private final Deque<long[]> samples = new ArrayDeque<>();   // {millis, chunksDone}

    /**
     * Records where the run is now.
     *
     * @param nowMillis   the clock, injected so the estimate is testable
     * @param chunksDone  total chunks processed so far, never decreasing
     */
    public void sample(long nowMillis, long chunksDone) {
        long[] last = samples.peekLast();
        if (last != null && nowMillis <= last[0]) {
            // A clock that did not move tells us nothing about rate, and a clock that went
            // backwards would produce a negative one. Ignore rather than poison the window.
            return;
        }
        samples.addLast(new long[]{nowMillis, chunksDone});
        while (samples.size() > WINDOW) {
            samples.removeFirst();
        }
    }

    /** Chunks per second over the window, or 0 when there is not yet enough to say. */
    public double ratePerSecond() {
        if (samples.size() < 2) {
            return 0.0;
        }
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long millis = last[0] - first[0];
        long chunks = last[1] - first[1];
        if (millis <= 0 || chunks <= 0) {
            return 0.0;
        }
        return chunks * 1000.0 / millis;
    }

    /**
     * Seconds remaining, or -1 when no honest estimate can be given yet.
     *
     * <p>-1 is returned rather than a guess in three cases: too few samples, a
     * rate at or near zero, and a total that is not yet known. Showing a made-up
     * number early is worse than showing none -- the player anchors on the first
     * figure they see, and a wrong one costs their trust in every later one.
     */
    public long secondsRemaining(long chunksDone, long chunksTotal) {
        if (chunksTotal <= 0 || chunksDone >= chunksTotal) {
            return -1;
        }
        double rate = ratePerSecond();
        if (rate < STALLED_RATE) {
            return -1;
        }
        return (long) Math.ceil((chunksTotal - chunksDone) / rate);
    }

    /** The estimate as a player-facing phrase. See {@link #humanTime(long)}. */
    public String describe(long chunksDone, long chunksTotal) {
        long seconds = secondsRemaining(chunksDone, chunksTotal);
        if (seconds < 0) {
            return "estimating...";
        }
        return "about " + humanTime(seconds) + " remaining";
    }

    /** Fraction complete, 0.0 to 1.0, for the bar. */
    public static double fraction(long chunksDone, long chunksTotal) {
        if (chunksTotal <= 0) {
            return 0.0;
        }
        if (chunksDone >= chunksTotal) {
            return 1.0;
        }
        return (double) chunksDone / chunksTotal;
    }

    /**
     * Seconds as something a person would say out loud.
     *
     * <p>Rounded deliberately: "1 hour 20 minutes" rather than "1:19:47". The
     * player is deciding whether to wait or press the button, and a
     * to-the-second countdown implies a precision this estimate does not have.
     */
    public static String humanTime(long seconds) {
        if (seconds < 0) {
            return "unknown";
        }
        if (seconds < 60) {
            return "less than a minute";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        String h = hours + (hours == 1 ? " hour" : " hours");
        if (restMinutes == 0) {
            return h;
        }
        return h + " " + restMinutes + (restMinutes == 1 ? " minute" : " minutes");
    }

    /** Forgets the history. Used when a run is resumed, so an old rate cannot leak into a new one. */
    public void reset() {
        samples.clear();
    }
}
