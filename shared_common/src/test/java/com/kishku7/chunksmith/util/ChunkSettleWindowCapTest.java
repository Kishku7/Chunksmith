package com.kishku7.chunksmith.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The frontier cap: the guard against the failure the neighbourhood rule alone does not cover.
 *
 * <p>{@link ChunkSettleWindowTest} proves the rule itself. These prove what happens when the rule's
 * assumption is false: chunks whose neighbourhood never closes, because the run skipped the ground next
 * to them. Without a cap those are held for the whole run, the leak behind the residency runaway
 * {@link ChunkResidency} documents.
 */
public class ChunkSettleWindowCapTest {

    /**
     * Offers a straight line of chunks and returns what the window released. No chunk in a line ever
     * gets all nine of its neighbours.
     *
     * @return the chunk x values released, in release order
     */
    private static List<Integer> offerLine(ChunkSettleWindow window, int count, long cap) {
        List<Integer> released = new ArrayList<>();
        for (int x = 0; x < count; x++) {
            int captured = x;
            window.offer(x, 0, 0L, () -> released.add(captured));
        }
        assertTrue("a line closes no neighbourhood",
                cap > 0 || released.isEmpty());
        return released;
    }

    @Test
    public void anUncappedFrontierGrowsForever() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L);
        offerLine(window, 5_000, 0L);
        assertEquals("this is the leak: every one of them is still held", 5_000, window.heldCount());
        assertEquals(0L, window.evictedCount());
    }

    @Test
    public void theCapBoundsTheFrontier() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L, 100L);
        offerLine(window, 5_000, 100L);
        assertEquals(100, window.heldCount());
        assertEquals("everything over the cap came back", 4_900L, window.evictedCount());
    }

    @Test
    public void evictionIsOldestFirst() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L, 3L);
        List<Integer> released = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            int captured = x;
            window.offer(x, 0, 0L, () -> released.add(captured));
        }
        assertEquals("the three oldest went, in order", List.of(0, 1, 2), released);
        assertEquals(3, window.heldCount());
    }

    @Test
    public void everyTicketReturns() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L, 10L);
        List<Integer> released = new ArrayList<>();
        for (int x = 0; x < 500; x++) {
            int captured = x;
            window.offer(x, 0, 0L, () -> released.add(captured));
        }
        window.drain();
        assertEquals("no ticket may be dropped and none may run twice", 500, released.size());
        assertEquals(500, released.stream().distinct().count());
        assertEquals(0, window.heldCount());
        assertEquals("bookkeeping is cleared", 0, window.trackedCount());
    }

    @Test
    public void slackCapDoesNothing() {
        ChunkSettleWindow window = new ChunkSettleWindow(0L, 100_000L);
        offerLine(window, 1_000, 100_000L);
        assertEquals(1_000, window.heldCount());
        assertEquals(0L, window.evictedCount());
    }
}
