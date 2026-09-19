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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops a pre-gen when the server cannot sustain it, and starts it again when it can.
 *
 * <p>Decided 2026-08-20 between "keep crawling", "pause with a clear message and resume when healthy",
 * and "push through regardless": the middle one, as the default, changeable live. Crawling is the wrong
 * default because a gated pre-gen on a server that cannot keep up does not stop: it stutters. Measured
 * on a live server: 60 chunks in two minutes, roughly 0.9 per second, with the never-wedge valve opening
 * every 120 seconds for about a second of work. That is indistinguishable from a hang, keeps the server
 * under load throughout, and makes no useful progress.
 *
 * <p>Both directions need patience: pausing on the first bad second would stop a run for a passing
 * autosave, and resuming on the first good second would restart it into the same wall. So each direction
 * requires the condition to hold continuously for the grace period, on one shared knob.
 */
public final class AutoPause {

    /**
     * Everything this class decides is now also SAID, in the server log.
     *
     * <p>mod_support #33: a reporter watched a run stop at 5.08% with "no warnings, no errors,
     * nothing". Every notice this feature had went to the console SENDER -- a chat channel, not
     * the log -- so on a client-hosted world the whole mechanism was invisible. A stop that
     * leaves no trace is indistinguishable from a hang, and four rounds of the ticket were spent
     * establishing which of the two it was. The log lines below exist so that question is never
     * asked again: the countdown starting, the pause, the recovery and the resume each say so.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static volatile boolean enabled = true;
    private static volatile long graceMillis = 120_000L;
    private static volatile long heapThresholdPercent = 85L;

    /**
     * The longest an auto-pause should last once the machine is coping again.
     *
     * <p>The owner's rule: "if a pause has to last more than 10 seconds, some number is wrong."
     * Pausing and resuming used to share ONE grace knob, so a 120-second pause-side patience also
     * made every recovery wait two minutes -- a pause of 10 seconds was impossible by
     * construction, whatever the machine did.
     *
     * <p>The two directions are not the same requirement. Patience going IN protects a healthy run
     * from a passing autosave and costs a little throughput if it is wrong. Patience coming OUT
     * costs the whole run. So the resume side gets its own, short, grace.
     */
    private static final long RESUME_GRACE_CAP_MS = 5_000L;

    /**
     * The width the next run should START at, or 0 for "use the configured ceiling".
     *
     * <p>Resuming at the width that just failed is how a run saws between generating and pausing.
     * A resumed run comes back narrow and climbs, so recovery is a step toward a width this
     * machine can hold rather than a return to the one it could not.
     */
    private static volatile int recommendedStartWidth;

    private static volatile long gatedSince;
    private static volatile long healthySince;

    private static volatile boolean autoPaused;
    private static volatile String pausedWorld;

    private AutoPause() {
    }

    /** Sets the auto-pause policy. Called when a run starts, from the config it was created with. */
    public static void configure(boolean enabled, long graceMillis, long heapThresholdPercent) {
        AutoPause.enabled = enabled;
        AutoPause.graceMillis = Math.max(1_000L, graceMillis);
        AutoPause.heapThresholdPercent = heapThresholdPercent;
    }

    /**
     * Decides whether the machine is well enough to carry a run again.
     *
     * <p>THE BUG THIS REPLACES (mod_support #33). The resume test used to be two hard-coded
     * absolutes -- {@code mspt <= 55} and {@code heap < 70} -- while the PAUSE side was
     * relative: heap from {@code throttleMaxHeapPercent}, and tick from the adaptive
     * {@link TickBudget#effectiveTarget()} which is allowed up to a 150 ms ceiling. The two
     * sides therefore answered different questions, and on any machine whose own baseline tick
     * cost exceeds ~55 ms the resume condition was unreachable BY CONSTRUCTION: the run could
     * auto-pause and never come back, whatever it did. A reporter sat at 5.08% indefinitely.
     *
     * <p>Note what makes it unreachable rather than merely strict: 55 ms is an ABSOLUTE compared
     * against a machine-RELATIVE reality. Once paused we stop dispatching, so the baseline
     * re-learns the machine's own idle cost and {@code effectiveTarget} rises to meet it -- the
     * run becomes sustainable at that target -- while the 55 ms test keeps asking the machine to
     * be faster than it has ever been. This is the same defect shape the throttle already carries
     * comments about twice: a trigger keyed to the wrong reference.
     *
     * <p>So both arms now use the pause side's own references. Heap: the same point the dispatch
     * gate itself reopens at, {@code throttleMaxHeapPercent - RESUME_MARGIN_PERCENT}, floored at
     * 50 exactly as {@link HeapPressure} floors it. Tick: at or under the effective target plus
     * the throttle's own dead-band.
     *
     * <p>An UNMEASURED target (-1, before a baseline exists) counts as healthy on the tick arm.
     * That is deliberate: refusing to resume on the absence of a measurement is how a latch gets
     * built, and the cost of being wrong is bounded -- the run resumes, finds it cannot sustain
     * itself, and auto-pauses again after the grace period, saying so in the log both times.
     */
    public static boolean healthyNow(double mspt, double heapUsedPercent) {
        double heapResumeAt = Math.max(50.0D,
                (double) heapThresholdPercent - HeapPressure.RESUME_MARGIN_PERCENT);
        boolean heapOk = heapUsedPercent >= 0.0D && heapUsedPercent < heapResumeAt;
        double target = TickBudget.effectiveTarget();
        boolean tickOk = mspt < 0.0D || target < 0.0D || mspt <= target + TickBudget.MSPT_BAND;
        return heapOk && tickOk;
    }

    /** One line naming which arm of {@link #healthyNow} is holding a paused run down. */
    public static String describeHealth(double mspt, double heapUsedPercent) {
        double heapResumeAt = Math.max(50.0D,
                (double) heapThresholdPercent - HeapPressure.RESUME_MARGIN_PERCENT);
        double target = TickBudget.effectiveTarget();
        return String.format("heap=%.1f%s (resume under %.0f) mspt=%.1f target=%s band=%.0f",
                heapUsedPercent, "pct", heapResumeAt, mspt,
                target < 0.0D ? "unmeasured" : String.format("%.1f", target),
                TickBudget.MSPT_BAND);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static long graceMillis() {
        return graceMillis;
    }

    /**
     * Records whether the server is currently unable to sustain the run.
     *
     * <p>"Our gate is closed" was too narrow. On a live server with the chunk gate off and the heap
     * under its threshold, nothing of ours ever closed while the server logged twelve "Can't keep up"
     * warnings and generation fell to 5 chunks per second. So it is either gate holding, OR the tick running
     * far past the target the throttle steers to.
     */
    public static void noteStruggling(boolean struggling, long now) {
        if (!struggling) {
            // Only worth a line if a countdown was actually running: this is called every pass.
            if (gatedSince != 0L && enabled && !autoPaused) {
                LOGGER.info("Chunksmith: the server recovered after {}s of struggling -- the"
                                + " auto-pause countdown is cancelled.",
                        Math.max(0L, (now - gatedSince) / 1000L));
            }
            gatedSince = 0L;
        } else if (gatedSince == 0L) {
            gatedSince = now;
            if (enabled && !autoPaused) {
                LOGGER.warn("Chunksmith: the server cannot sustain the pre-gen. If this holds for"
                                + " {}s the run will AUTO-PAUSE and resume by itself once the"
                                + " server recovers.",
                        graceMillis / 1000L);
            }
        }
    }

    /** @deprecated use {@link #shouldPause(long, boolean)}, so no caller silently loses the floor test. */
    @Deprecated
    public static boolean shouldPause(long now) {
        return shouldPause(now, true);
    }

    /**
     * True once generation has been held continuously for the whole grace period AND there is
     * nothing left to try.
     *
     * <p>{@code atHardMinimum} is the owner's rule in code: pausing "should be a last resort, and
     * only after a good hard try". The controller can always make itself narrower, and a narrower
     * run still generates; an auto-pause generates nothing. So a run with room left to slow down
     * slows down instead, and only a run already at {@link DispatchControl#MIN_WORKING_WIDTH} that
     * STILL cannot keep up has earned a pause.
     *
     * <p>This used to fire on time alone, which is how a client with a perfectly usable width of
     * 16 available to it stopped dead at 5.08% instead (mod_support #33).
     *
     * @param atHardMinimum true when the dispatch width can go no lower
     */
    public static boolean shouldPause(long now, boolean atHardMinimum) {
        return enabled && !autoPaused && atHardMinimum
                && gatedSince != 0L && now - gatedSince >= graceMillis;
    }

    public static long strugglingSeconds(long now) {
        return gatedSince == 0L ? 0L : Math.max(0L, (now - gatedSince) / 1000L);
    }

    /** Records that we paused this world, so only our own pause is ever auto-resumed. */
    public static void markAutoPaused(String world) {
        autoPaused = true;
        pausedWorld = world;
        gatedSince = 0L;
        healthySince = 0L;
    }

    public static boolean isAutoPaused() {
        return autoPaused;
    }

    public static String pausedWorld() {
        return pausedWorld;
    }

    /** Records whether the server currently looks well enough to carry a run. */
    public static void noteHealthy(boolean healthy, long now) {
        if (!healthy) {
            healthySince = 0L;
        } else if (healthySince == 0L) {
            healthySince = now;
        }
    }

    /**
     * How long health must hold before a paused run restarts. Short, and never the pause-side
     * grace -- see {@link #RESUME_GRACE_CAP_MS}.
     */
    public static long resumeGraceMillis() {
        return Math.min(graceMillis, RESUME_GRACE_CAP_MS);
    }

    /** True once the server has looked healthy continuously for the resume grace. */
    public static boolean shouldResume(long now) {
        return enabled && autoPaused && healthySince != 0L
                && now - healthySince >= resumeGraceMillis();
    }

    /** The width a resumed run should start at, or 0 when there is no recommendation. */
    public static int recommendedStartWidth() {
        return recommendedStartWidth;
    }

    /**
     * Records that the width in use was not survivable, so the next run starts below it.
     *
     * <p>Halved rather than reused: the run reached this width by stepping DOWN to it and still
     * could not hold it, so it is an upper bound on what works, not a target.
     */
    public static void noteWidthFailed(int width) {
        recommendedStartWidth = Math.max(DispatchControl.MIN_WORKING_WIDTH, width / 2);
    }

    /**
     * Says in the LOG that an auto-paused run is being restarted.
     *
     * <p>It lives here rather than at the mixin call site so every loader and version cell gets
     * the same line from one place, and so the logger dependency stays in shared_common.
     */
    public static void logAutoResumed(String world, long healthySeconds) {
        LOGGER.warn("Chunksmith: AUTO-RESUMING the pre-gen for {} -- the server has looked healthy"
                        + " for {}s.",
                world == null ? "the paused world" : world, healthySeconds);
    }

    public static void clearAutoPaused() {
        autoPaused = false;
        pausedWorld = null;
        healthySince = 0L;
        gatedSince = 0L;
    }

    /** Clears every remembered state, for a human pause, a new run, or a stopping server. */
    public static void clear() {
        autoPaused = false;
        pausedWorld = null;
        gatedSince = 0L;
        healthySince = 0L;
        recommendedStartWidth = 0;
    }

    /** Returns one line for the debug command. No literal percent sign, because the sender formats it. */
    public static String describe() {
        return String.format("enabled=%s grace=%ds autoPaused=%s world=%s",
                enabled, graceMillis / 1000L, autoPaused, pausedWorld == null ? "none" : pausedWorld);
    }
}
