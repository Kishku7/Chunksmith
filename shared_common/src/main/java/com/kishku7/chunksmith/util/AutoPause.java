package com.kishku7.chunksmith.util;

/**
 * Stops a pre-gen when the server cannot sustain it, and starts it again when it can.
 *
 * <p><b>The policy.</b> Decided 2026-08-20, choosing between "keep crawling", "pause with a clear
 * message and resume when healthy", and "push through regardless": the middle one, as the DEFAULT,
 * changeable on the fly. This class is that decision.
 *
 * <p><b>Why crawling is the wrong default.</b> On a server that cannot keep up, a gated pre-gen does
 * not stop -- it stutters. Measured on a live server: 60 chunks in two minutes, roughly 0.9 per
 * second, with the never-wedge valve opening every 120 seconds for about a second of work. That is
 * indistinguishable from a hang to anyone watching, it keeps the server under load the whole time,
 * and it produces no useful progress. A run that stops and SAYS why is strictly better than one that
 * limps invisibly, and one that starts itself again when the pressure lifts costs the operator
 * nothing.
 *
 * <p><b>Both directions need patience.</b> Pausing on the first bad second would stop a run for a
 * passing autosave; resuming on the first good second would restart it into the same wall. So each
 * direction requires the condition to hold CONTINUOUSLY for the grace period, and any moment to the
 * contrary resets the clock. One knob controls both, because an operator who wants a hair trigger
 * wants it symmetrically.
 *
 * <p>MC-free and clock-injectable, so the state machine is testable without a server.
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

    /** Called when a run starts, from the config that run was created with. */
    public static void configure(final boolean enabled, final long graceMillis) {
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
     * Report whether generation is currently held by one of our throttle gates.
     *
     * <p>Gated is the right signal rather than raw tick time: it already means "we have stopped
     * dispatching and the server still is not recovering", which is exactly the condition that makes
     * continuing pointless. Reading tick health directly would also fire on load that has nothing to
     * do with us.
     */
    public static void noteGated(final boolean gated, final long now) {
        if (!gated) {
            gatedSince = 0L;
        } else if (gatedSince == 0L) {
            gatedSince = now;
        }
    }

    /** True once generation has been held continuously for the whole grace period. */
    public static boolean shouldPause(final long now) {
        return enabled && !autoPaused && gatedSince != 0L && now - gatedSince >= graceMillis;
    }

    /** How long the current hold has lasted, in seconds, for the message. */
    public static long gatedSeconds(final long now) {
        return gatedSince == 0L ? 0L : Math.max(0L, (now - gatedSince) / 1000L);
    }

    /** Record that WE paused this world, so only our own pause is ever auto-resumed. */
    public static void markAutoPaused(final String world) {
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

    /** Report whether the server currently looks well enough to carry a run. */
    public static void noteHealthy(final boolean healthy, final long now) {
        if (!healthy) {
            healthySince = 0L;
        } else if (healthySince == 0L) {
            healthySince = now;
        }
    }

    /** True once the server has looked healthy continuously for the whole grace period. */
    public static boolean shouldResume(final long now) {
        return enabled && autoPaused && healthySince != 0L && now - healthySince >= graceMillis;
    }

    /** Called once the resume has actually been issued. */
    public static void clearAutoPaused() {
        autoPaused = false;
        pausedWorld = null;
        healthySince = 0L;
        gatedSince = 0L;
    }

    /** A human pause, a new run, or a stopping server -- forget everything. */
    public static void clear() {
        autoPaused = false;
        pausedWorld = null;
        gatedSince = 0L;
        healthySince = 0L;
    }

    /** No literal percent sign: the sender formats this string. */
    public static String describe() {
        return String.format("enabled=%s grace=%ds autoPaused=%s world=%s",
                enabled, graceMillis / 1000L, autoPaused, pausedWorld == null ? "none" : pausedWorld);
    }
}
