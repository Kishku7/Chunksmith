package com.kishku7.chunksmith.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The measurements that replaced a guessed constant.
 *
 * <p>The failure these exist to prevent is specific and was seen in production: a server whose idle
 * tick cost already equalled the configured target, where an absolute-target throttle could never
 * observe a healthy tick, pinned dispatch at its floor and throttled a run to 2 chunks/sec -- for
 * 10 ms of load it was causing out of 85.
 */
public class TickBudgetTest {

    private static void settle(final double mspt, final boolean working, final int players, final int n) {
        for (int i = 0; i < n; i++) {
            TickBudget.sample(mspt, working, players);
        }
    }

    @Before
    public void reset() {
        TickBudget.reset();
        TickBudget.configure(25L, 20L, 0L);
    }

    @After
    public void tearDown() {
        TickBudget.reset();
    }

    @Test
    public void nothingIsClaimedBeforeAnythingIsMeasured() {
        assertTrue(TickBudget.baseline() < 0.0D);
        assertTrue(TickBudget.ourCost() < 0.0D);
        assertEquals("caller must fall back to its absolute target", -1.0D, TickBudget.effectiveTarget(), 0.001D);
    }

    @Test
    public void theVeryFIRSTSampleIsNotSwallowedByInitialisation() {
        // 3.9.0 reset on a player-count change and RETURNED, and lastPlayerCount starts at -1 -- so
        // the first call of every run was discarded. That call is also the only moment a run has
        // nothing in flight, so the baseline could never be learned and the throttle silently fell
        // back to its absolute target, pinned at 2/50.
        TickBudget.sample(74.9D, false, 0);
        assertTrue("the first sample must count", TickBudget.baseline() > 0.0D);
        assertEquals(74.9D, TickBudget.baseline(), 0.001D);
    }

    @Test
    public void baselineIsWhatTheServerCostsWithNothingOfOursInFlight() {
        settle(74.9D, false, 0, 60);
        assertEquals(74.9D, TickBudget.baseline(), 0.5D);
    }

    @Test
    public void baselineRISESToo_itIsNotARunningMinimum() {
        // The first attempt at this tracked the cheapest reading ever seen, so it anchored low and
        // the effective target silently collapsed back to the absolute one.
        settle(48.0D, false, 0, 60);
        assertEquals(48.0D, TickBudget.baseline(), 0.5D);
        settle(75.0D, false, 0, 120);
        assertTrue("the baseline must follow the server upward", TickBudget.baseline() > 70.0D);
    }

    @Test
    public void ourCostIsTheDifferenceWeMake() {
        settle(74.9D, false, 0, 60);
        settle(88.4D, true, 0, 120);
        assertEquals("13.5ms, which is what was measured live", 13.5D, TickBudget.ourCost(), 1.5D);
    }

    @Test
    public void theAllowanceIsTwiceWhatWeCost() {
        settle(50.0D, false, 0, 60);
        settle(70.0D, true, 0, 200);
        assertEquals("20ms measured -> 40ms allowed", 40.0D, TickBudget.allowance(), 3.0D);
    }

    @Test
    public void theConfiguredBudgetIsAFloorNotACeiling() {
        settle(50.0D, false, 0, 60);
        settle(51.0D, true, 0, 200);   // we cost ~1ms, doubled is 2ms
        assertEquals("floored at the configured 25", 25.0D, TickBudget.allowance(), 1.0D);
    }

    @Test
    public void theTargetIsTheServersOwnCostPlusOurAllowance() {
        settle(74.9D, false, 0, 60);
        settle(88.4D, true, 0, 200);
        // baseline ~74.9 + allowance max(25, 2*13.5) = 27  ->  ~102
        assertTrue(TickBudget.effectiveTarget() > 95.0D);
        assertTrue(TickBudget.effectiveTarget() < 110.0D);
    }

    @Test
    public void theAllowanceCannotRUNAWAY() {
        // Live failure: the allowance is twice our cost, and a pre-gen pushes until it reaches its
        // allowance -- so cost chased allowance chased cost. TickBudget#MAX_ALLOWANCE_FACTOR has the
        // numbers; this is the clamp that stops it.
        settle(50.0D, false, 0, 60);
        settle(500.0D, true, 0, 400);   // a preposterous measured cost
        assertTrue("must be clamped, not doubled forever", TickBudget.allowance() <= 75.0D + 0.001D);
        assertTrue(TickBudget.effectiveTarget() < 130.0D);
    }

    @Test
    public void theCeilingScalesWithTheConfiguredFloor() {
        TickBudget.configure(10L, 0L, 0L);
        settle(50.0D, false, 0, 60);
        settle(500.0D, true, 0, 400);
        assertTrue("floor 10 -> ceiling 30", TickBudget.allowance() <= 30.0D + 0.001D);
    }

    @Test
    public void eachPlayerTakesRoomOutOfOurAllowance() {
        settle(50.0D, false, 0, 60);
        settle(90.0D, true, 0, 200);
        final double empty = TickBudget.allowance();

        TickBudget.reset();
        TickBudget.configure(25L, 20L, 0L);
        settle(50.0D, false, 2, 60);
        settle(90.0D, true, 2, 200);
        assertTrue("two players must cost us 40ms of allowance", TickBudget.allowance() < empty - 30.0D);
    }

    @Test
    public void weNeverYieldOurselvesDownToNothing() {
        settle(50.0D, false, 10, 60);
        settle(55.0D, true, 10, 200);
        assertTrue("ten players would reserve 200ms; a floor keeps the run alive",
                TickBudget.allowance() >= 5.0D);
    }

    @Test
    public void aJoinOrLeaveThrowsTheMeasurementsAwayRatherThanDecayingToThem() {
        settle(50.0D, false, 0, 60);
        settle(70.0D, true, 0, 200);
        assertEquals(50.0D, TickBudget.baseline(), 0.5D);
        assertTrue(TickBudget.ourCost() > 0.0D);

        // Somebody joined and the server now costs 90. The learned values must be DISCARDED and the
        // new reading taken at face value -- not blended with the old one, which would leave the
        // throttle steering by a number from before the join for many seconds.
        TickBudget.sample(90.0D, false, 1);
        assertEquals("a step change is adopted whole, not decayed toward",
                90.0D, TickBudget.baseline(), 0.001D);
        assertTrue("our own cost is meaningless against the old baseline", TickBudget.ourCost() < 0.0D);
    }

    @Test
    public void anUnreadableTickIsIgnoredRatherThanAveragedIn() {
        settle(74.9D, false, 0, 60);
        final double before = TickBudget.baseline();
        TickBudget.sample(-1.0D, false, 0);
        assertEquals(before, TickBudget.baseline(), 0.001D);
    }

    @Test
    public void theBaselineIsRemeasuredPeriodicallyRatherThanTrustedForEver() {
        // Live failure: the baseline read 50.2ms for fifteen minutes while the server's real cost
        // climbed past 125ms, so the whole increase was attributed to us -- ourCost "measured" 76.4ms
        // against a true ~16ms and the throttle collapsed to 1/50 on a number that was long stale.
        final long t0 = 1_000_000L;
        assertFalse("no probe on the very first call", TickBudget.shouldProbe(t0));
        assertFalse("nor before the interval has elapsed", TickBudget.shouldProbe(t0 + 30_000L));

        assertTrue("a minute on, stop and look", TickBudget.shouldProbe(t0 + 60_000L));
        assertTrue("and hold for the probe duration", TickBudget.isProbing());
        assertTrue(TickBudget.shouldProbe(t0 + 61_000L));

        assertFalse("then release", TickBudget.shouldProbe(t0 + 62_001L));
        assertFalse(TickBudget.isProbing());
        assertFalse("and wait a full interval before the next one",
                TickBudget.shouldProbe(t0 + 90_000L));
        assertTrue(TickBudget.shouldProbe(t0 + 122_002L));
    }

    @Test
    public void aMOMENTARYgapBetweenChunksIsNotABaselineReading() {
        // The failure TickBudget#IDLE_TICKS_BEFORE_TRUSTED exists for. "Nothing in flight" is true for
        // a tick or two between one chunk landing and the next dispatching, and that tick is still
        // paying for the chunk that just landed -- its save, its unload, the GC of what it allocated.
        // Sampled, it teaches the baseline our own aftermath and bills the server for it.
        settle(50.0D, false, 0, 60);
        assertEquals(50.0D, TickBudget.baseline(), 0.5D);

        // a burst of work, then two idle ticks -- the shape of a gap between dispatches
        for (int i = 0; i < 40; i++) {
            TickBudget.sample(60.0D, true, 0);
            TickBudget.sample(140.0D, false, 0);
            TickBudget.sample(140.0D, false, 0);
        }
        assertEquals("a gap is not idle: the baseline must not have moved",
                50.0D, TickBudget.baseline(), 0.5D);
    }

    @Test
    public void sustainedIDLEstillMovesTheBaseline_soAPausedRunReMeasures() {
        // The other half of the same rule. Ignoring brief gaps must NOT mean ignoring real idle --
        // a paused run, a held probe, or a server with no pregen at all is exactly when the honest
        // readings are available, and refusing them would leave the baseline frozen for ever.
        settle(50.0D, false, 0, 60);
        settle(90.0D, false, 0, 120);
        assertTrue("sustained idle is a real reading", TickBudget.baseline() > 85.0D);
    }

    @Test
    public void anAbsoluteCeilingStopsTheTargetWanderingIntoUnplayableTerritory() {
        // The absolute ceiling from TickBudget#effectiveTarget. Without it the adaptive target wanders
        // into territory nothing else objects to, because the heap gate is under its threshold and
        // auto-pause compares against this very target.
        TickBudget.configure(25L, 0L, 150L);
        settle(163.9D, false, 0, 60);
        settle(200.0D, true, 0, 200);
        assertTrue("the adaptive target wants to go far higher", TickBudget.atCeiling());
        assertEquals("but it is held at the ceiling", 150.0D, TickBudget.effectiveTarget(), 0.001D);
    }

    @Test
    public void aHealthyServerNeverNoticesTheCeiling() {
        TickBudget.configure(25L, 0L, 150L);
        settle(50.0D, false, 0, 60);
        settle(66.0D, true, 0, 200);
        assertFalse(TickBudget.atCeiling());
        assertTrue(TickBudget.effectiveTarget() < 150.0D);
    }

    @Test
    public void aCeilingOfZeroDisablesIt() {
        TickBudget.configure(25L, 0L, 0L);
        settle(300.0D, false, 0, 60);
        settle(400.0D, true, 0, 200);
        assertFalse(TickBudget.atCeiling());
        assertTrue("no ceiling means the adaptive target stands", TickBudget.effectiveTarget() > 300.0D);
    }

    @Test
    public void describeIsSafeToHandToAFormatter() {
        settle(74.9D, false, 0, 60);
        assertFalse(TickBudget.describe().contains("%"));
        assertTrue(String.format(TickBudget.describe()).length() > 0);
    }
}
