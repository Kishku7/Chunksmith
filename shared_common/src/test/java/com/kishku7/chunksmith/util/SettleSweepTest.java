package com.kishku7.chunksmith.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashSet;

/**
 * Where the settle sweep is allowed to stop -- and, more importantly, where it is not.
 *
 * <p>The dangerous mistake here is not stopping too rarely, it is stopping too early: loading a window
 * that overlaps ungenerated ground does not re-read that ground, it generates it, off-pattern and outside
 * the task's own accounting. Every eligibility test below exists to pin that shut.
 */
public class SettleSweepTest {

    private static void fill(final SettleSweep sweep, final int x0, final int z0,
                             final int x1, final int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                sweep.markGenerated(x, z);
            }
        }
    }

    private static List<String> drain(SettleSweep sweep) {
        List<String> stops = new ArrayList<>();
        int[] stop;
        while ((stop = sweep.nextStop()) != null) {
            stops.add(stop[0] + "," + stop[1]);
        }
        return stops;
    }

    /** The rule. Nothing is eligible while any chunk of its window is still missing. */
    @Test
    public void aWindowWithUngeneratedGroundIsNeverIssued() {
        SettleSweep sweep = new SettleSweep(0, 0, 30, 30, 3);
        fill(sweep, 0, 0, 29, 29);
        // Punch one hole in the middle of the very first window.
        SettleSweep holed = new SettleSweep(0, 0, 30, 30, 3);
        for (int x = 0; x <= 29; x++) {
            for (int z = 0; z <= 29; z++) {
                if (!(x == 2 && z == 2)) {
                    holed.markGenerated(x, z);
                }
            }
        }

        assertTrue("the intact sweep can start", sweep.windowGenerated(0, 0));
        assertFalse("one missing chunk disqualifies the whole window", holed.windowGenerated(0, 0));
    }

    @Test
    public void nothingIssuedBeforeGeneration() {
        SettleSweep sweep = new SettleSweep(0, 0, 20, 20, 3);
        assertNull(sweep.nextStop());
        assertEquals(0, sweep.stopsIssued());
    }

    @Test
    public void everyStopOnce() {
        SettleSweep sweep = new SettleSweep(0, 0, 12, 12, 3);
        fill(sweep, -8, -8, 20, 20);

        List<String> stops = drain(sweep);

        assertEquals(sweep.stopCount(), stops.size());
        assertEquals("no stop repeats", stops.size(), new HashSet<>(stops).size());
        assertTrue(sweep.isComplete());
        assertNull("a completed sweep hands out nothing more", sweep.nextStop());
    }

    @Test
    public void theStopsCoverTheWholeArea() {
        int w = 20;
        int h = 17;
        int r = 4;
        SettleSweep sweep = new SettleSweep(0, 0, w, h, r);
        fill(sweep, -10, -10, 40, 40);

        boolean[][] covered = new boolean[w][h];
        int[] stop;
        while ((stop = sweep.nextStop()) != null) {
            for (int x = stop[0] - r; x <= stop[0] + r; x++) {
                for (int z = stop[1] - r; z <= stop[1] + r; z++) {
                    if (x >= 0 && z >= 0 && x < w && z < h) {
                        covered[x][z] = true;
                    }
                }
            }
        }
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                assertTrue("chunk " + x + "," + z + " was never inside a window", covered[x][z]);
            }
        }
    }

    @Test
    public void finishedGroundSweepsEarly() {
        SettleSweep sweep = new SettleSweep(0, 0, 40, 10, 3);
        // Only the western third exists so far.
        fill(sweep, 0, 0, 13, 9);

        int[] first = sweep.nextStop();
        assertNotNull("the finished part must not wait for the rest", first);
        assertTrue("and the stop must be inside the finished part", first[0] <= 13);

        // Everything eligible now is in the west; the east is still off-limits.
        List<String> early = drain(sweep);
        for (String s : early) {
            assertTrue("no stop may be issued in ungenerated ground: " + s,
                    Integer.parseInt(s.split(",")[0]) <= 13);
        }

        // Now the rest arrives and the remaining stops become available.
        fill(sweep, 14, 0, 39, 9);
        assertNotNull("the east becomes eligible once it exists", sweep.nextStop());
    }

    @Test
    public void edgeStopsAreNotStranded() {
        SettleSweep sweep = new SettleSweep(0, 0, 9, 9, 4);
        fill(sweep, 0, 0, 8, 8);   // exactly the bounds and not one chunk more

        assertTrue("out-of-bounds neighbours are not required", sweep.windowGenerated(0, 0));
        assertEquals(sweep.stopCount(), drain(sweep).size());
        assertTrue(sweep.isComplete());
    }

    @Test
    public void negativeBoundsAreHandled() {
        SettleSweep sweep = new SettleSweep(-20, -20, 12, 12, 3);
        fill(sweep, -30, -30, 0, 0);

        List<String> stops = drain(sweep);
        assertEquals(sweep.stopCount(), stops.size());
        for (String s : stops) {
            int x = Integer.parseInt(s.split(",")[0]);
            assertTrue("stops stay inside the task bounds", x >= -20 && x < -8);
        }
    }

    @Test
    public void anEmptyAreaIsHarmless() {
        SettleSweep sweep = new SettleSweep(0, 0, 0, 0, 5);
        assertEquals(0, sweep.stopCount());
        assertNull(sweep.nextStop());
        assertTrue(sweep.isComplete());
    }

    @Test
    public void radiusIsAtLeastOne() {
        assertEquals(1, new SettleSweep(0, 0, 4, 4, 0).radius());
        assertEquals(1, new SettleSweep(0, 0, 4, 4, -7).radius());
    }
}
