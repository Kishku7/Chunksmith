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

    private static volatile boolean enabled = true;
    private static volatile long graceMillis = 120_000L;

    private static volatile long gatedSince;
    private static volatile long healthySince;

    private static volatile boolean autoPaused;
    private static volatile String pausedWorld;

    private AutoPause() {
    }

    /** Sets the auto-pause policy. Called when a run starts, from the config it was created with. */
    public static void configure(boolean enabled, long graceMillis) {
        AutoPause.enabled = enabled;
        AutoPause.graceMillis = Math.max(1_000L, graceMillis);
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
            gatedSince = 0L;
        } else if (gatedSince == 0L) {
            gatedSince = now;
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
