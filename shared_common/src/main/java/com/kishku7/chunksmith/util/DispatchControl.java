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

/**
 * The arithmetic of the dispatch-width controller, separated from the run it drives so the shape
 * of the loop can be tested without a server (mod_support #33).
 *
 * <p>The controller lives in {@code GenerationTask}: it owns the atomics, the sampling cadence and
 * the decision about WHEN the machine is overloaded. What lives here is only WHAT to do once that
 * decision is made -- which is the part that was wrong, and the part worth pinning.
 */
public final class DispatchControl {

    private DispatchControl() {
    }

    /**
     * The next width after a back-off.
     *
     * <p>Additive for an isolated sample: one tick over budget is noise, and halving on noise would
     * throw away throughput a healthy server has earned. Multiplicative once the overload persists,
     * because stepping down one at a time takes as many samples as the distance to a width the
     * machine can actually carry -- 84 of them, from a ceiling of 100 to a workable 16, which is
     * twenty seconds of a two-core client being pinned before the controller arrives.
     *
     * @param current   the width now
     * @param sustained true once the overload has persisted rather than spiked
     * @return the width to move to, never below 1
     */
    public static int reduce(int current, boolean sustained) {
        return reduce(current, sustained, false);
    }

    /**
     * The next width after a back-off, with the option to go below the comfort floor.
     *
     * <p>{@code mayGoBelowFloor} is the difference between throttling and STOPPING, and the owner
     * settled it: "even if the working numbers are outside of the ideal, some work is better than
     * no work", and "if a pause has to last more than 10 seconds, some number is wrong". The floor
     * is a comfort setting -- it exists so an ordinary wobble does not collapse the width and then
     * spend a minute climbing back. It is NOT a reason to stop generating. A machine that cannot
     * carry {@link #MIN_USEFUL_WIDTH} can still carry three, and three chunks in flight is worth
     * more than an auto-pause that produces nothing.
     *
     * <p>So the caller opens this once the overload has persisted rather than spiked, and the
     * width walks down one at a time to {@link #MIN_WORKING_WIDTH}. Auto-pause is only allowed to
     * fire once even that is not enough -- see {@code AutoPause.shouldPause}.
     *
     * @param current         the width now
     * @param sustained       true once the overload has persisted rather than spiked
     * @param mayGoBelowFloor true once a good hard try at the floor has already failed
     * @return the width to move to, never below {@link #MIN_WORKING_WIDTH}
     */
    public static int reduce(int current, boolean sustained, boolean mayGoBelowFloor) {
        if (current <= MIN_USEFUL_WIDTH) {
            if (mayGoBelowFloor) {
                // One at a time down here: the numbers are small, and halving 8 to 4 to 2 throws
                // away half the remaining throughput on each step for no measurement gained.
                return Math.max(MIN_WORKING_WIDTH, current - 1);
            }
            // Already at or below the floor. Halving again only buys a longer climb back.
            return Math.max(MIN_WORKING_WIDTH, current);
        }
        int next = sustained ? current / 2 : current - 1;
        return Math.max(MIN_USEFUL_WIDTH, next);
    }

    /**
     * The narrowest width that still counts as generating.
     *
     * <p>Below this there is no run left to throttle, so this is where "try harder" ends and
     * auto-pause finally becomes the honest answer.
     */
    public static final int MIN_WORKING_WIDTH = 1;

    /**
     * The narrowest width the controller will THROTTLE down to.
     *
     * <p>Measured, on a Paper server pinned to two cores: with no floor, halving from a ceiling of
     * 200 reaches a width of 1 in about eight steps, and the run then sits at 1-2 chunks in flight
     * for minutes while climbing back at one per second. Two of five runs at that ceiling took
     * twice as long as the best, and the throttle notices show 92 separate reports of width 1
     * against 50 at a ceiling of 50 -- the deeper the ceiling, the longer the collapse.
     *
     * <p>A width of 1 is not throttling, it is stopping: per-chunk latency is over a second, so
     * one in flight is a handful of chunks a minute. Whatever the server is struggling with, the
     * difference between 1 and 8 in flight is not what saves it, and the cost of finding out is a
     * minute of climbing.
     *
     * <p>Deliberately equal to the config floor: the same number that is the smallest sensible
     * ceiling is the smallest sensible operating width.
     */
    public static final int MIN_USEFUL_WIDTH = 8;

    /**
     * The slow-start threshold a run should OPEN with.
     *
     * <p>The threshold decides how fast {@code rampUp} is allowed to widen: below it the recovery
     * may burst eight steps a sample, at or above it the climb is one step at a time. A fresh run
     * knows nothing bad about any width, so it opens at the ceiling and a healthy server behaves
     * exactly as it always did.
     *
     * <p>A RESUMED run is different and this is the bug it fixes (mod_support #36). It used to
     * open at the ceiling too. So a run auto-paused at a width of 1, and deliberately resumed at a
     * width of 1, was immediately permitted to burst all the way back toward a ceiling of 200 --
     * and did, in about one sample, straight into the wall that had just stopped it. Resuming
     * narrow bought nothing at all, because the narrow width was not what governed the climb.
     *
     * <p>So a resumed run inherits the width that FAILED as its threshold: the climb may still be
     * quick up to the last thing we know did not work, and is one careful step at a time past it.
     * Floored at {@link #MIN_USEFUL_WIDTH} for the reason the back-off path already documents --
     * a threshold below the floor is a memory of the collapse, not of anything that worked.
     *
     * @param maxWorkingCount the configured ceiling
     * @param failedWidth     the width the last auto-pause fired at, or 0 if this is a fresh run
     */
    public static int resumeThreshold(int maxWorkingCount, int failedWidth) {
        if (failedWidth <= 0) {
            return maxWorkingCount;
        }
        return Math.max(MIN_USEFUL_WIDTH, Math.min(maxWorkingCount, failedWidth));
    }

    /**
     * Whether the fast multi-step recovery is allowed at this width.
     *
     * <p>Below the widest setting known to have run healthily, a burst is a return to a proven
     * state and costs nothing. At or above it, it is a guess -- and repeating that guess every time
     * the box recovers is the generate-pause-generate sawtooth the reporter described. Past the
     * threshold the climb is one step at a time.
     *
     * @param current   the width now
     * @param goodWidth the widest setting known to have run healthily on this machine
     * @return true if the width may climb in bursts rather than single steps
     */
    public static boolean mayBurst(int current, int goodWidth) {
        return current < goodWidth;
    }
}
