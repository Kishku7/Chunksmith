package com.kishku7.chunksmith.lod.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The injected-region set -- and the TWO bugs it exists to prevent.
 *
 * <p>1. The shipped 3.1.0-beta-2 set was keyed by packed region x/z ALONE. Region (0,0) is a different place
 * in every dimension, so the moment the overworld's (0,0) was injected the Nether's (0,0) was treated as
 * already done and skipped for the rest of the session -- silently, with every counter reporting success.
 * {@link #theNetherIsNotTheOverworld()} is that bug, pinned.
 *
 * <p>2. Keying on (dimension, x, z) and nothing else answers "have I drawn this region?" when the question
 * the injector needs answered is "have I drawn THIS VERSION of it?". A pregen does not only create new
 * regions -- it keeps GROWING the ones under the player for hours. The sync notices, the cache notices, the
 * downloader re-fetches the bigger file, and then the injector would look up the coordinates, find them, and
 * throw the new data on the floor. {@link #aRegionThatCHANGEDMustBeInjectedAgain()} is that bug, pinned
 * before it ever shipped.
 */
public class InjectedRegionsTest {

    private static final String OVERWORLD = "minecraft_overworld";
    private static final String NETHER = "minecraft_the_nether";
    private static final String END = "minecraft_the_end";

    /** A freshness token. Its VALUE is meaningless -- only sameness and difference matter. */
    private static final long V1 = 0x1111_2222_3333_4444L;
    private static final long V2 = 0x5555_6666_7777_8888L;

    /** THE FIRST BUG. Same region coordinates, different dimension: both must be injectable. */
    @Test
    public void theNetherIsNotTheOverworld() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue("the overworld's region (0,0) is new", injected.claim(OVERWORLD, 0, 0, V1));
        assertFalse("...and is not claimed twice", injected.claim(OVERWORLD, 0, 0, V1));

        // Under the old x/z-only key this returned false and the Nether's terrain was never drawn.
        assertTrue("the NETHER's region (0,0) is a different place and must still be injected",
                injected.claim(NETHER, 0, 0, V1));
        assertTrue("...and so is the End's", injected.claim(END, 0, 0, V1));

        assertEquals(3, injected.size());
    }

    /**
     * THE SECOND BUG. A region we have already drawn, whose token has MOVED, is a different region as far as
     * the renderer is concerned -- and it is the whole reason the periodic sync is worth having.
     */
    @Test
    public void aRegionThatCHANGEDMustBeInjectedAgain() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue("first sight of the region", injected.claim(OVERWORLD, 4, -2, V1));
        assertFalse("the same version is never re-injected", injected.claim(OVERWORLD, 4, -2, V1));

        // The pregen grew it. The server advertises a new token; the client re-downloaded it. If this
        // returns false the freshly-downloaded terrain is silently discarded and the player's world stays
        // frozen at the version they joined with.
        assertTrue("a region whose token moved MUST be injected again", injected.claim(OVERWORLD, 4, -2, V2));
        assertFalse("...but only once, at the new version", injected.claim(OVERWORLD, 4, -2, V2));

        assertEquals("re-claiming replaces, it does not duplicate", 1, injected.size());
        assertEquals("and we remember WHICH version we drew",
                Long.valueOf(V2), injected.injectedHash(OVERWORLD, 4, -2));
    }

    /** Going BACK to a version we already drew is still a change, and is still re-injected. */
    @Test
    public void anyDifferentTokenCountsAsChanged() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(OVERWORLD, 0, 0, V1));
        assertTrue(injected.claim(OVERWORLD, 0, 0, V2));
        assertTrue("a token we saw two versions ago is not the token we drew", injected.claim(OVERWORLD, 0, 0, V1));
        assertEquals(Long.valueOf(V1), injected.injectedHash(OVERWORLD, 0, 0));
    }

    @Test
    public void aRegionIsClaimedExactlyOncePerDimension() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(NETHER, -3, 7, V1));
        assertFalse(injected.claim(NETHER, -3, 7, V1));
        assertTrue(injected.contains(NETHER, -3, 7));

        // A different region in the same dimension is still new.
        assertTrue(injected.claim(NETHER, -3, 8, V1));
        assertFalse(injected.contains(OVERWORLD, -3, 7));
    }

    /** A region we could not read, or could not inject, must be retried -- not skipped forever. */
    @Test
    public void releasingLetsALaterRefreshRetryIt() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(OVERWORLD, 5, 5, V1));
        injected.release(OVERWORLD, 5, 5);
        assertFalse(injected.contains(OVERWORLD, 5, 5));
        assertNull(injected.injectedHash(OVERWORLD, 5, 5));
        assertTrue("a released region is claimable again", injected.claim(OVERWORLD, 5, 5, V1));

        // Releasing one dimension's region must not release another's.
        assertTrue(injected.claim(NETHER, 5, 5, V1));
        injected.release(OVERWORLD, 5, 5);
        assertTrue("the Nether's claim survives the overworld's release", injected.contains(NETHER, 5, 5));
    }

    /**
     * A release must FORGET the region, not restore the version that was there before it.
     *
     * <p>An upgrade that was claimed and then abandoned (the player walked through a portal mid-inject) must
     * leave us saying "I do not know what the renderer has", so the next index re-claims and re-injects.
     * Restoring the old token would leave us believing we had drawn a version we had not.
     */
    @Test
    public void releasingAnUpgradeForgetsTheRegionEntirely() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(OVERWORLD, 2, 2, V1));
        assertTrue("the region grew", injected.claim(OVERWORLD, 2, 2, V2));
        injected.release(OVERWORLD, 2, 2);

        assertFalse(injected.contains(OVERWORLD, 2, 2));
        assertTrue("even the version we HAD drawn must be re-claimable", injected.claim(OVERWORLD, 2, 2, V1));
    }

    @Test
    public void negativeCoordinatesRoundTrip() {
        final InjectedRegions injected = new InjectedRegions();

        assertTrue(injected.claim(OVERWORLD, -1, -1, V1));
        assertFalse(injected.claim(OVERWORLD, -1, -1, V1));
        // -1,-1 packed into a long is 0xFFFFFFFFFFFFFFFF; 1,1 must not alias it.
        assertTrue(injected.claim(OVERWORLD, 1, 1, V1));
        assertTrue(injected.claim(OVERWORLD, -1, 1, V1));
        assertTrue(injected.claim(OVERWORLD, 1, -1, V1));
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
        injected.claim(OVERWORLD, 0, 0, V1);
        injected.claim(NETHER, 0, 0, V1);
        assertEquals(2, injected.size());

        injected.clear();

        assertEquals(0, injected.size());
        assertTrue(injected.claim(OVERWORLD, 0, 0, V1));
    }
}
