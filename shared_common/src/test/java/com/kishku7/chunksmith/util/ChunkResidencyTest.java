package com.kishku7.chunksmith.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one thing that must never happen here: an absent measurement reading as an empty server.
 *
 * <p>The throttle gates on this number. "Unknown" has to stay -1 all the way through, because a caller
 * that mistakes it for 0 opens the taps on exactly the server that could not tell it how loaded it was.
 */
public class ChunkResidencyTest {

    @Before
    public void reset() {
        ChunkResidency.clear();
    }

    @After
    public void tearDown() {
        ChunkResidency.clear();
    }

    @Test
    public void unknownBeforeAnythingIsPublished() {
        assertEquals(-1L, ChunkResidency.loadedChunks());
        assertFalse(ChunkResidency.isSupported());
    }

    @Test
    public void reportsWhatWasPublished() {
        ChunkResidency.report(75_045L);
        assertEquals(75_045L, ChunkResidency.loadedChunks());
        assertTrue(ChunkResidency.isSupported());
    }

    @Test
    public void zeroIsARealReadingAndNotAnAbsentOne() {
        ChunkResidency.report(0L);
        assertEquals(0L, ChunkResidency.loadedChunks());
        assertTrue("a genuinely empty server is supported, not unknown", ChunkResidency.isSupported());
    }

    @Test
    public void aPlatformThatCannotSayIsIgnoredRatherThanBelieved() {
        ChunkResidency.report(1_234L);
        ChunkResidency.report(-1L);
        assertEquals("a negative report must not erase a good reading", 1_234L, ChunkResidency.loadedChunks());
    }

    @Test
    public void clearingMeansNoReadingOutlivesItsServer() {
        ChunkResidency.report(1_234L);
        ChunkResidency.clear();
        assertEquals(-1L, ChunkResidency.loadedChunks());
    }
}
