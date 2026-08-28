package com.kishku7.chunksmith.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The settle window and the bug it exists to fix (mod_support #14).
 *
 * <p>A pregen dropped each chunk's ticket the instant generation finished, so a mod that reacts to a new
 * chunk on a later tick found the chunk and its neighbours already unloaded; see
 * {@link ChunkSettleWindow} for the mod, the version and the counts.
 *
 * <p>The rule under test is spatial, and the two directions matter equally: a chunk must not be released
 * while its neighbourhood is still open (that is the bug), and it MUST be released once the sweep has
 * moved past (a ticket we forget to drop is a chunk that never unloads, which would turn a pregen's flat
 * memory profile into a leak).
 */
public class ChunkSettleWindowTest {

    /** Records which chunks were released, in order, so a test can assert both fact and timing. */
    private static final class Recorder {
        private final List<String> released = new ArrayList<>();

        Runnable of(int x, int z) {
            return () -> this.released.add(x + "," + z);
        }

        boolean has(int x, int z) {
            return this.released.contains(x + "," + z);
        }

        int count() {
            return this.released.size();
        }
    }

    /** The bug. A lone chunk has no neighbours yet, so it must still be held. */
    @Test
    public void aChunkWithAnOpenNeighbourhoodIsNotReleased() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L);
        Recorder rec = new Recorder();

        window.offer(0, 0, 0L, rec.of(0, 0));

        assertEquals("held while the neighbourhood is open", 0, rec.count());
        assertEquals(1, window.heldCount());
        assertFalse(window.neighbourhoodComplete(0, 0));
    }

    @Test
    public void releasedWhenTheNeighbourhoodCloses() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L);
        Recorder rec = new Recorder();

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                window.offer(x, z, 0L, rec.of(x, z));
            }
        }

        assertTrue("the centre is complete", rec.has(0, 0));
        // The eight around it are still on the frontier because each is missing neighbours of its own.
        assertFalse(rec.has(1, 1));
        assertEquals(8, window.heldCount());
    }

    @Test
    public void theDelayHoldsPastCompletion() {
        ChunkSettleWindow window = new ChunkSettleWindow(40L);
        Recorder rec = new Recorder();

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                window.offer(x, z, 100L, rec.of(x, z));
            }
        }
        assertEquals("complete but not yet due", 0, rec.count());
        assertTrue("all nine have arrived", window.neighbourhoodComplete(0, 0));
        assertFalse("a frontier chunk is still missing neighbours", window.neighbourhoodComplete(1, 1));

        window.releaseDue(139L);
        assertEquals("still one tick early", 0, rec.count());

        window.releaseDue(140L);
        assertTrue("due at exactly now + delay", rec.has(0, 0));
    }

    @Test
    public void onlyTheFrontierIsHeld() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L);
        Recorder rec = new Recorder();

        // Three rows tall, sweeping east. After column x, every chunk in column x-1 is fully surrounded.
        for (int x = 0; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) {
                window.offer(x, z, x, rec.of(x, z));
            }
        }

        // The middle of each completed column is gone; the frontier and the un-neighboured rows remain.
        assertTrue(rec.has(1, 0));
        assertTrue(rec.has(5, 0));
        assertFalse("the leading column is still the frontier", rec.has(6, 0));
        assertTrue("held set does not grow with the sweep",
                window.heldCount() <= 21);
    }

    @Test
    public void drainReleasesAll() {
        ChunkSettleWindow window = new ChunkSettleWindow(1000L);
        Recorder rec = new Recorder();

        for (int x = 0; x < 5; x++) {
            window.offer(x, 0, 0L, rec.of(x, 0));
        }
        assertEquals(0, rec.count());

        window.drain();

        assertEquals("a finished run leaves nothing loaded", 5, rec.count());
        assertEquals(0, window.heldCount());
        assertEquals(5L, window.releasedCount());
    }

    @Test
    public void offeredTwiceReleasesOnce() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L);
        Recorder rec = new Recorder();

        window.offer(3, 3, 0L, rec.of(3, 3));
        window.offer(3, 3, 0L, rec.of(3, 3));
        window.drain();

        assertEquals(1, rec.count());
        assertEquals(1L, window.releasedCount());
    }

    @Test
    public void negativeCoordinatesAreDistinct() {
        assertFalse(ChunkSettleWindow.key(-1, 0) == ChunkSettleWindow.key(0, -1));
        assertFalse(ChunkSettleWindow.key(-1, -1) == ChunkSettleWindow.key(1, 1));

        ChunkSettleWindow window = new ChunkSettleWindow(0L);
        Recorder rec = new Recorder();
        for (int x = -5; x <= -3; x++) {
            for (int z = -5; z <= -3; z++) {
                window.offer(x, z, 0L, rec.of(x, z));
            }
        }
        assertTrue(rec.has(-4, -4));
    }

    @Test
    public void emptyDrain() {
        ChunkSettleWindow window = new ChunkSettleWindow(20L);
        window.drain();
        assertEquals(0, window.heldCount());
        assertEquals(0L, window.releasedCount());
    }
}
