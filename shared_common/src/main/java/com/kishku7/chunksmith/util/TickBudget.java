package com.kishku7.chunksmith.util;

/**
 * What the server costs without us, what we cost, and therefore how hard we may push.
 *
 * <p>The throttle used to steer on absolute tick time, which cannot work. Measured on a live server, the
 * tick cost 74.9 ms with the pre-gen paused against a configured target of 75, so the ramp window was
 * unreachable whatever Chunksmith did: the governor pinned dispatch at its floor permanently and
 * throttled the run to 2 chunks/sec, while the run itself cost 10 ms.
 *
 * <p>So three measurements instead of one assumption. The <i>baseline</i> is the tick cost with nothing
 * of ours in flight -- a decaying average, and never a running minimum: the first attempt tracked the
 * cheapest reading ever seen, so it anchored at 48 ms while the server had moved on to 75, and the
 * effective target silently collapsed back to the old absolute one. <i>Our cost</i> is the tick cost
 * while we are working, minus the baseline; measured 13.5 ms on the server that exposed all of this,
 * against a 25 ms figure somebody had picked out of the air. The <i>allowance</i> is twice our measured
 * cost, so ordinary variance does not trip the governor and it tracks what the terrain actually costs.
 *
 * <p>Players get reserved room rather than just the absence of harm. A player's cost is already in the
 * baseline, so a rising baseline stops Chunksmith making things worse -- but gives the player nothing
 * back. Each online player therefore also shrinks our allowance by {@code playerReserveMillis}.
 *
 * <p>A join or a leave invalidates the baseline immediately: it is a step change in what the server
 * costs, and a decaying average would take far too long to follow it.
 */
public final class TickBudget {

    /** Weight of each new sample in the decaying averages. Slow enough to ignore single spikes. */
    private static final double ALPHA = 0.1D;

    /** Never let the allowance be squeezed below this, however many players are on. */
    private static final double MIN_ALLOWANCE_MS = 5.0D;

    /**
     * Ceiling on the allowance, as a multiple of the configured floor.
     *
     * <p>Runaway without it, and it did run away: the allowance is twice our measured cost and a pre-gen
     * pushes until it reaches it, so the cost climbs toward the allowance, which doubles it again. Live,
     * over ten minutes -- ourCost 16.5 ms -> 154 ms, allowance 32.9 ms -> 308 ms, a 358 ms target, the
     * throttle never backing off, the server near 2.8 TPS.
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
     * <p>The baseline only updates on ticks where Chunksmith has nothing in flight, which during a
     * running pre-gen is almost never -- so without this it is measured once at the start and trusted
     * for ever. Observed live: the baseline read 50.2 ms for fifteen minutes while the server's real
     * cost climbed past 125 ms under GC pressure, all of it attributed to us -- ourCost "measured"
     * 76.4 ms against a true ~16 ms, the allowance slammed into its ceiling, the throttle collapsed to
     * 1/50. Two seconds every two minutes is 1.7 percent.
     */
    private static final long PROBE_INTERVAL_MS = 60_000L;

    /** How long to hold dispatch while the probe reading settles. */
    private static final long PROBE_DURATION_MS = 2_000L;

    /**
     * How many ticks of unbroken idle before an idle tick counts as a baseline reading.
     *
     * <p>Dispatch stopping is not our load stopping: a chunk that just landed is still being saved,
     * still being unloaded, and its garbage still being collected, all on ticks where our in-flight
     * count already reads zero. Sampling those teaches the baseline our own aftermath. Measured on a
     * live server: the baseline read 49ms, then 116.8ms minutes later with no load change. A run of idle
     * ticks separates the cases -- a gap between dispatches is one or two ticks, a held probe or a
     * paused run is idle indefinitely.
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
     * @param mspt smoothed tick cost right now, or negative if the platform cannot say
     */
    public static void sample(final double mspt, final boolean ourWorkInFlight, final int players) {
        if (players != lastPlayerCount) {
            // A join or a leave is a step change in what the server costs, so throw the learned values
            // away and re-measure. DO NOT return here: the first call of a run always trips this branch
            // (lastPlayerCount starts at -1) and is the only moment a run has nothing in flight, so
            // discarding it meant the baseline was never learned at all -- effectiveTarget stayed -1 and
            // the run was pinned at 2/50. Reset, then use the sample.
            lastPlayerCount = players;
            baselineMspt = -1.0D;
            ourCostMspt = -1.0D;
            consecutiveIdleTicks = 0;
        }
        if (mspt < 0.0D) {
            return;
        }
        if (!ourWorkInFlight) {
            // An idle tick is not automatically a baseline tick: the moment between one chunk completing
            // and the next dispatching is still paying for the chunk that just landed. See
            // IDLE_TICKS_BEFORE_TRUSTED; the exception is a run's first sample, where idle really is idle.
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
        // Clamp before the player reserve, so the ceiling bounds what we ask for and the reserve
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
        // Past this ceiling the run yields, whatever the adaptive target says. A target derived from a
        // measured baseline correctly stops Chunksmith throttling itself for load it did not cause --
        // and, unbounded, stops it defending the server at all: observed live, a baseline of 163.9 ms
        // gave a 238.9 ms target, steering toward roughly 4 TPS with nothing objecting, because the heap
        // gate was under its threshold and auto-pause compares against this very target. So this is the
        // one bound that is absolute rather than relative.
        return Math.min(adaptive, (double) ceilingMillis);
    }

    public static boolean atCeiling() {
        return ceilingMillis > 0L && baselineMspt >= 0.0D
                && baselineMspt + allowance() > (double) ceilingMillis;
    }

    /**
     * Should the run stop dispatching right now to re-measure the baseline? True for
     * {@link #PROBE_DURATION_MS} once every {@link #PROBE_INTERVAL_MS}; while it is true the caller must
     * not dispatch, so the ticks that follow are genuine baseline samples.
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
