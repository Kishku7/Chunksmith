package com.kishku7.chunksmith.util;

/**
 * What the server costs without us, what WE cost, and therefore how hard we may push.
 *
 * <p><b>Why an absolute tick target cannot work.</b> The throttle used to steer on absolute tick
 * time: back off above {@code target + band}, ramp below {@code target - band}. Measured on a live
 * server, the tick cost <b>74.9 ms with the pre-gen PAUSED</b> against a configured target of 75. The
 * ramp window was unreachable no matter what Chunksmith did, so the governor pinned dispatch at its
 * floor permanently and throttled the run to 2 chunks/sec -- while the run itself was costing 10 ms.
 *
 * <p><b>So measure three things instead of assuming one.</b>
 *
 * <ul>
 *   <li><b>Baseline</b> -- the tick cost with nothing of ours in flight. That is the SERVER's cost,
 *       players included. A decaying average, never a running minimum: the first attempt at this
 *       tracked the cheapest reading ever seen, so it anchored at 48 ms while the server had moved on
 *       to 75, and the effective target silently collapsed back to the old absolute one.
 *   <li><b>Our cost</b> -- the tick cost while we ARE working, minus the baseline. Measured, not
 *       guessed. It was 13.5 ms on the server that exposed all of this, against a 25 ms figure
 *       somebody had picked out of the air.
 *   <li><b>Allowance</b> -- twice our measured cost. Twice, so ordinary variance does not trip the
 *       governor on every sample; measured, so it tracks whatever the terrain is actually costing
 *       rather than a number that was right once.
 * </ul>
 *
 * <p><b>Players get reserved room, not just the absence of harm.</b> A player's cost is already IN the
 * baseline, so a rising baseline stops Chunksmith making things worse -- but it does not give the player
 * anything back. Each online player therefore also SHRINKS our allowance by {@code playerReserveMillis}.
 *
 * <p><b>A join or a leave invalidates the baseline immediately.</b> It is a step change in what the
 * server costs, and a decaying average would take far too long to follow it.
 */
public final class TickBudget {

    /** Weight of each new sample in the decaying averages. Slow enough to ignore single spikes. */
    private static final double ALPHA = 0.1D;

    /** Never let the allowance be squeezed below this, however many players are on. */
    private static final double MIN_ALLOWANCE_MS = 5.0D;

    /**
     * Ceiling on the allowance, as a multiple of the configured floor.
     *
     * <p><b>Without this the model runs away, and it did.</b> The allowance is twice our measured cost,
     * and a pre-gen pushes until it REACHES its allowance -- so the cost climbs toward the allowance,
     * which doubles it again. Observed live: ourCost 16.5 ms -> 154 ms and allowance 32.9 ms -> 308 ms in
     * ten minutes, giving a 358 ms target at which the throttle would never back off and the server sits
     * near 2.8 TPS. Clamping the allowance breaks the loop: once it stops rising, the cost stops being
     * pushed upward and settles at what the work genuinely costs.
     */
    private static final double MAX_ALLOWANCE_FACTOR = 3.0D;

    private static volatile double baselineMspt = -1.0D;
    private static volatile double ourCostMspt = -1.0D;
    private static volatile int lastPlayerCount = -1;

    private static volatile long minAllowanceMillis = 25L;
    private static volatile long playerReserveMillis = 20L;
    private static volatile long ceilingMillis = 150L;

    /**
     * How often to stop dispatching briefly and take a clean baseline reading.
     *
     * <p><b>Why a run must deliberately interrupt itself.</b> The baseline only updates on ticks where
     * Chunksmith has nothing in flight, and during a running pre-gen that is almost never true -- so
     * it is measured once at the start and then trusted for ever. Observed live: the baseline read
     * 50.2 ms for fifteen minutes while the server's real cost climbed past 125 ms under GC pressure
     * from a growing resident set. Every millisecond of that increase was attributed to US, ourCost
     * "measured" 76.4 ms against a true ~16 ms, the allowance slammed into its ceiling, and the
     * throttle collapsed to 1/50 steering by a number that had been wrong for a quarter of an hour.
     *
     * <p>Nothing separates "the server got slower" from "we got more expensive" without occasionally
     * stopping to look. Two seconds every two minutes is about 1.7 percent of throughput.
     */
    private static final long PROBE_INTERVAL_MS = 60_000L;

    /** How long to hold dispatch while the probe reading settles. */
    private static final long PROBE_DURATION_MS = 2_000L;

    /**
     * How many ticks of UNBROKEN idle before an idle tick counts as a baseline reading.
     *
     * <p>Dispatch stopping is not the same as our load stopping. A chunk that just landed is still
     * being saved, still being unloaded, and the garbage it made is still being collected -- all on
     * ticks where our in-flight count already reads zero. Sampling those teaches the baseline our own
     * aftermath and then bills the server for it. Measured live 2026-08-20: the baseline read 49ms,
     * then 116.8ms minutes later with no load change, no keep-up warnings, and the box at 225 percent
     * of 800 available. The throttle target is derived from this number, so a wrong baseline steers
     * everything downstream.
     *
     * <p>Requiring a RUN of idle ticks separates the two cases without the caller having to say which it
     * is in: a gap between two dispatches is one or two ticks and never qualifies, while a held probe, a
     * paused run, or a server with no pregen is idle indefinitely and qualifies once the tail drains.
     */
    private static final int IDLE_TICKS_BEFORE_TRUSTED = 15;

    private static volatile int consecutiveIdleTicks;

    private static volatile long lastProbeEndedAt;
    private static volatile long probeStartedAt;

    private TickBudget() {
    }

    /** Called when a run starts, from the config that run was created with. */
    public static void configure(final long minAllowanceMillis, final long playerReserveMillis,
                                 final long ceilingMillis) {
        TickBudget.minAllowanceMillis = Math.max(0L, minAllowanceMillis);
        TickBudget.playerReserveMillis = Math.max(0L, playerReserveMillis);
        TickBudget.ceilingMillis = Math.max(0L, ceilingMillis);
    }

    /**
     * Feed one tick-health sample.
     *
     * @param mspt        smoothed tick cost right now, or negative if the platform cannot say
     * @param ourWorkInFlight whether Chunksmith had work outstanding for this sample
     * @param players     how many players are online
     */
    public static void sample(final double mspt, final boolean ourWorkInFlight, final int players) {
        if (players != lastPlayerCount) {
            // A join or a leave is a STEP CHANGE in what the server costs, so throw the learned values
            // away and re-measure rather than steer by a stale number. They return within a few samples.
            //
            // DO NOT return here. The first call of a run always trips this branch (lastPlayerCount
            // starts at -1), and the ONLY moment a run has nothing in flight is that same first call,
            // before the first chunk is dispatched. Discarding it meant the baseline could never be
            // learned at all: effectiveTarget stayed -1, the throttle fell back to its absolute
            // target, and the run was pinned at 2/50 exactly as before. Reset, then USE the sample.
            lastPlayerCount = players;
            baselineMspt = -1.0D;
            ourCostMspt = -1.0D;
            consecutiveIdleTicks = 0;
        }
        if (mspt < 0.0D) {
            return;
        }
        if (!ourWorkInFlight) {
            // NOT every idle tick is a baseline tick: the moment between one chunk completing and the
            // next dispatching is still paying for the chunk that just landed -- its save, its unload
            // pass, the GC of what it allocated. See IDLE_TICKS_BEFORE_TRUSTED for the measured cost of
            // getting this wrong. The exception is a run's very first sample, where idle really is idle.
            if (++consecutiveIdleTicks < IDLE_TICKS_BEFORE_TRUSTED && baselineMspt >= 0.0D) {
                return;
            }
            baselineMspt = baselineMspt < 0.0D ? mspt : (baselineMspt * (1.0D - ALPHA)) + (mspt * ALPHA);
            return;
        }
        consecutiveIdleTicks = 0;
        if (baselineMspt < 0.0D) {
            return;
        }
        final double cost = Math.max(0.0D, mspt - baselineMspt);
        ourCostMspt = ourCostMspt < 0.0D ? cost : (ourCostMspt * (1.0D - ALPHA)) + (cost * ALPHA);
    }

    /** Tick cost attributable to the server itself, players included. -1 until measured. */
    public static double baseline() {
        return baselineMspt;
    }

    /** Tick cost attributable to Chunksmith. -1 until measured. */
    public static double ourCost() {
        return ourCostMspt;
    }

    /** How much tick time we may add: twice our measured cost, less what is reserved for players. */
    public static double allowance() {
        final double doubled = ourCostMspt < 0.0D ? minAllowanceMillis : ourCostMspt * 2.0D;
        final double ceiling = Math.max(minAllowanceMillis, 1.0D) * MAX_ALLOWANCE_FACTOR;
        // Clamp BEFORE the player reserve, so the ceiling bounds what we ask for and the reserve
        // still takes its cut out of whatever we were granted.
        final double granted = Math.min(ceiling, Math.max(doubled, minAllowanceMillis));
        final double reserved = (double) lastPlayerCount * playerReserveMillis;
        return Math.max(MIN_ALLOWANCE_MS, granted - Math.max(0.0D, reserved));
    }

    /**
     * The tick cost to steer to, or -1 when there is not enough measurement yet and the caller should
     * fall back to its configured absolute target.
     */
    public static double effectiveTarget() {
        if (baselineMspt < 0.0D) {
            return -1.0D;
        }
        final double adaptive = baselineMspt + allowance();
        if (ceilingMillis <= 0L) {
            return adaptive;
        }
        // ABSOLUTE PLAYABILITY CEILING. Deriving the target from a measured baseline correctly stops
        // Chunksmith throttling itself for load it did not cause -- and, left unbounded, stops it
        // defending the server at all. Observed live: baseline 163.9 ms gave a 238.9 ms target, so
        // the throttle was steering toward roughly 4 TPS and nothing objected, because the heap gate
        // was still under its threshold and auto-pause compares against this very target.
        //
        // Past the ceiling the server is not playable whoever is to blame, so the run yields. It is
        // the one bound that must NOT be relative: every relative bound moves with the thing it is
        // supposed to be protecting against.
        return Math.min(adaptive, (double) ceilingMillis);
    }

    public static boolean atCeiling() {
        return ceilingMillis > 0L && baselineMspt >= 0.0D
                && baselineMspt + allowance() > (double) ceilingMillis;
    }

    /**
     * Should the run stop dispatching right now to re-measure the baseline?
     *
     * <p>Called from the dispatch loop. Returns true for {@link #PROBE_DURATION_MS} once every
     * {@link #PROBE_INTERVAL_MS}; while it is true the caller must not dispatch, so the ticks that
     * follow are genuine baseline samples.
     */
    public static boolean shouldProbe(final long now) {
        if (probeStartedAt != 0L) {
            if (now - probeStartedAt < PROBE_DURATION_MS) {
                return true;
            }
            probeStartedAt = 0L;
            lastProbeEndedAt = now;
            return false;
        }
        if (lastProbeEndedAt == 0L) {
            lastProbeEndedAt = now;
            return false;
        }
        if (now - lastProbeEndedAt >= PROBE_INTERVAL_MS) {
            probeStartedAt = now;
            return true;
        }
        return false;
    }

    /** True while a baseline probe is in progress -- the caller is holding dispatch for it. */
    public static boolean isProbing() {
        return probeStartedAt != 0L;
    }

    /** Forget everything -- a new run, or a server going away. */
    public static void reset() {
        baselineMspt = -1.0D;
        ourCostMspt = -1.0D;
        lastPlayerCount = -1;
        consecutiveIdleTicks = 0;
        probeStartedAt = 0L;
        lastProbeEndedAt = 0L;
    }

    /** No literal percent sign: the sender formats this string. */
    public static String describe() {
        return String.format(
                "baseline=%s ourCost=%s allowance=%.1fms target=%s players=%d reservePerPlayer=%dms probing=%s",
                baselineMspt < 0.0D ? "unmeasured" : String.format("%.1fms", baselineMspt),
                ourCostMspt < 0.0D ? "unmeasured" : String.format("%.1fms", ourCostMspt),
                allowance(),
                effectiveTarget() < 0.0D ? "unmeasured" : String.format("%.1fms", effectiveTarget()),
                Math.max(0, lastPlayerCount),
                playerReserveMillis,
                probeStartedAt != 0L)
                + (atCeiling() ? String.format(" AT-CEILING(%dms)", ceilingMillis) : "");
    }
}
