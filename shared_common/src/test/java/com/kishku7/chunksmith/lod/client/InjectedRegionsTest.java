package com.kishku7.chunksmith.lod.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The once-per-session injected-region set -- and the CROSS-DIMENSION COLLISION it exists to prevent.
 *
 * <p>The shipped 3.1.0-beta-2 set was keyed by packed region x/z ALONE. Region (0,0) is a different place in
 * every dimension, so the moment the overworld's (0,0) was injected the Nether's (0,0) was treated as
 * already done and skipped for the rest of the session -- silently, with every counter reporting success.
 * {@link #theNetherIsNotTheOverworld()} is that bug, pinned.
 */
public class InjectedRegionsTest {

    private static final String OVERWORLD = "minecraft_overworld";
    private static final String NETHER = "minecraft_the_nether";
    private static final String END = "minecraft_the_end";

    /** THE BUG. Same region coordinates, different dimension: both must be injectable. */
    @Test
    public void theNetherIsNotTheOverworld() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue("the overworld's region (0,0) is new", injected.claim(OVERWORLD, 0, 0));
        assertFalse("...and is not claimed twice", injected.claim(OVERWORLD, 0, 0));

        // Under the old x/z-only key this returned false and the Nether's terrain was never drawn.
        assertTrue("the NETHER's region (0,0) is a different place and must still be injected",
                injected.claim(NETHER, 0, 0));
        assertTrue("...and so is the End's", injected.claim(END, 0, 0));

        assertEquals(3, injected.size());
    }

    @Test
    public void aRegionIsClaimedExactlyOncePerDimension() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(NETHER, -3, 7));
        assertFalse(injected.claim(NETHER, -3, 7));
        assertTrue(injected.contains(NETHER, -3, 7));

        // A different region in the same dimension is still new.
        assertTrue(injected.claim(NETHER, -3, 8));
        assertFalse(injected.contains(OVERWORLD, -3, 7));
    }

    /** A region we could not read, or could not inject, must be retried -- not skipped forever. */
    @Test
    public void releasingLetsALaterRefreshRetryIt() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(OVERWORLD, 5, 5));
        injected.release(OVERWORLD, 5, 5);
        assertFalse(injected.contains(OVERWORLD, 5, 5));
        assertTrue("a released region is claimable again", injected.claim(OVERWORLD, 5, 5));

        // Releasing one dimension's region must not release another's.
        assertTrue(injected.claim(NETHER, 5, 5));
        injected.release(OVERWORLD, 5, 5);
        assertTrue("the Nether's claim survives the overworld's release", injected.contains(NETHER, 5, 5));
    }

    @Test
    public void negativeCoordinatesRoundTrip() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(OVERWORLD, -1, -1));
        assertFalse(injected.claim(OVERWORLD, -1, -1));
        // -1,-1 packed into a long is 0xFFFFFFFFFFFFFFFF; 1,1 must not alias it.
        assertTrue(injected.claim(OVERWORLD, 1, 1));
        assertTrue(injected.claim(OVERWORLD, -1, 1));
        assertTrue(injected.claim(OVERWORLD, 1, -1));
        assertEquals(4, injected.size());
    }

    /** The key must not let a dimension id and a coordinate run together into the same string. */
    @Test
    public void keysCannotCollideAcrossTheSeparator() {
        assertNotEquals(InjectedRegions.key("a", 1, 2), InjectedRegions.key("a/1", 2, 2));
        assertEquals("minecraft_the_nether/-3,7", InjectedRegions.key(NETHER, -3, 7));
    }

    @Test
    public void clearForgetsEverything() {
        final InjectedRegions injected = new InjectedRegions();
        injected.claim(OVERWORLD, 0, 0);
        injected.claim(NETHER, 0, 0);
        assertEquals(2, injected.size());

        injected.clear();

        assertEquals(0, injected.size());
        assertTrue(injected.claim(OVERWORLD, 0, 0));
    }
}
