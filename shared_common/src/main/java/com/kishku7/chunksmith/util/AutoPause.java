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

    /** True once generation has been held continuously for the whole grace period. */
    public static boolean shouldPause(long now) {
        return enabled && !autoPaused && gatedSince != 0L && now - gatedSince >= graceMillis;
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

    /** True once the server has looked healthy continuously for the whole grace period. */
    public static boolean shouldResume(long now) {
        return enabled && autoPaused && healthySince != 0L && now - healthySince >= graceMillis;
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
    }

    /** Returns one line for the debug command. No literal percent sign, because the sender formats it. */
    public static String describe() {
        return String.format("enabled=%s grace=%ds autoPaused=%s world=%s",
                enabled, graceMillis / 1000L, autoPaused, pausedWorld == null ? "none" : pausedWorld);
    }
}
