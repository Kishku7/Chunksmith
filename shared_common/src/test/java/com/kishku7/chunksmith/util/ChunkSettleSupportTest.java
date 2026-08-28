package com.kishku7.chunksmith.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The tick pump, added in 3.5.1.
 *
 * <p>Before it, the only production caller of {@code releaseDue} was the window's own {@code offer()},
 * so a held ticket came back only when a new chunk arrived. That is fine while a run is flowing and
 * wrong the moment it is not: with dispatch held by the residency gate there are no arrivals, so the
 * frontier could not shrink, so residency could not fall, so the gate stayed shut. These tests exist
 * to prove a release now depends on time passing and not on more work being dispatched.
 */
public class ChunkSettleSupportTest {

    @Before
    public void reset() {
        ChunkSettleSupport.forget();
        ChunkSettleSupport.configure(true, 40L, 0L);
    }

    @After
    public void tearDown() {
        ChunkSettleSupport.forget();
        ChunkSettleSupport.configure(false, 0L, 0L);
    }

    @Test
    public void aWindowHandedOutIsAWindowThePumpKnowsAbout() {
        assertEquals(0, ChunkSettleSupport.liveWindowCount());
        ChunkSettleSupport.newWindow();
        ChunkSettleSupport.newWindow();
        assertEquals(2, ChunkSettleSupport.liveWindowCount());
    }

    @Test
    public void settlingOffStillHandsBackNullAndRegistersNothing() {
        ChunkSettleSupport.configure(false, 40L, 0L);
        assertEquals(null, ChunkSettleSupport.newWindow());
        assertEquals(0, ChunkSettleSupport.liveWindowCount());
    }

    @Test
    public void theTickReleasesWithoutAnyNewChunkArriving() {
        ChunkSettleSupport.configure(true, 40L, 0L);
        final ChunkSettleWindow window = ChunkSettleSupport.newWindow();
        final List<String> released = new ArrayList<>();

        // A closed 3x3 around (0,0): the centre's neighbourhood is complete, so it becomes DUE at
        // tick 40 -- but nothing else will ever be offered, which is the situation under test.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                final String name = x + "," + z;
                window.offer(x, z, 0L, () -> released.add(name));
            }
        }
        assertTrue("nothing is due yet at tick 0", released.isEmpty());

        ChunkSettleSupport.tick(10L);
        assertTrue("still inside the delay", released.isEmpty());

        ChunkSettleSupport.tick(40L);
        assertEquals("time alone released it -- no offer() was needed", List.of("0,0"), released);
    }

    @Test
    public void aDrainedWindowIsDroppedRatherThanPumpedForEver() {
        final ChunkSettleWindow window = ChunkSettleSupport.newWindow();
        window.offer(0, 0, 0L, () -> { });
        assertEquals(1, ChunkSettleSupport.liveWindowCount());

        window.drain();
        ChunkSettleSupport.tick(1L);
        assertEquals("the adapter should not have to remember to deregister",
                0, ChunkSettleSupport.liveWindowCount());
    }

    @Test
    public void pumpingWithNoWindowsIsHarmless() {
        ChunkSettleSupport.tick(1L);
        ChunkSettleSupport.tick(2L);
        assertEquals(0, ChunkSettleSupport.liveWindowCount());
    }

    @Test
    public void forgetDropsEverythingBecauseTheServerIsGoingAway() {
        ChunkSettleSupport.newWindow();
        ChunkSettleSupport.newWindow();
        ChunkSettleSupport.forget();
        assertEquals(0, ChunkSettleSupport.liveWindowCount());
    }
}
