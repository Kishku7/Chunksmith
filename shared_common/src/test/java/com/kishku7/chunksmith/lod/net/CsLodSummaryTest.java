package com.kishku7.chunksmith.lod.net;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * The summary fold -- the thing that makes an idle sync poll cost nothing.
 *
 * <p>Two properties, and the sync is wrong without either of them:
 * <ul>
 *   <li><b>order-independent</b>, because the server folds a {@code Files.list} (filesystem order) and the
 *       client folds the entries of an index (wire order). Same set, different order, same answer -- or the
 *       two sides disagree forever and pull a full index every interval;</li>
 *   <li><b>change-detecting</b>, for every way a set of regions can differ: one added, one removed, one
 *       changed, one moved.</li>
 * </ul>
 */
public class CsLodSummaryTest {

    /** A region, as the fold sees it. */
    private record R(int x, int z, long hash) {
    }

    private static long fold(final List<R> regions) {
        long aggregate = 0L;
        for (final R r : regions) {
            aggregate = CsLodSummary.fold(aggregate, r.x(), r.z(), r.hash());
        }
        return aggregate;
    }

    private static List<R> store() {
        final List<R> regions = new ArrayList<>();
        regions.add(new R(0, 0, 0x1111L));
        regions.add(new R(0, 1, 0x2222L));
        regions.add(new R(1, 0, 0x3333L));
        regions.add(new R(-1, -1, 0x4444L));
        regions.add(new R(-2, 3, 0x5555L));
        return regions;
    }

    /** THE property. The server's readdir order is not the client's index order. */
    @Test
    public void theFoldIsOrderIndependent() {
        final List<R> a = store();
        final List<R> b = new ArrayList<>(a);
        Collections.reverse(b);
        final List<R> c = new ArrayList<>(a);
        Collections.shuffle(c, new java.util.Random(42));

        assertEquals("reversed", fold(a), fold(b));
        assertEquals("shuffled", fold(a), fold(c));
    }

    @Test
    public void anEmptySetFoldsToZero() {
        assertEquals(0L, fold(List.of()));
        assertEquals(0, CsLodSummary.Snapshot.EMPTY.count());
        assertEquals(0L, CsLodSummary.Snapshot.EMPTY.aggregate());
    }

    @Test
    public void anAddedRegionMovesTheAggregate() {
        final List<R> grown = store();
        grown.add(new R(4, 4, 0x6666L));
        assertNotEquals(fold(store()), fold(grown));
    }

    @Test
    public void aRemovedRegionMovesTheAggregate() {
        final List<R> lost = store();
        lost.remove(2);
        assertNotEquals(fold(store()), fold(lost));
    }

    @Test
    public void aChangedRegionMovesTheAggregate() {
        final List<R> changed = store();
        changed.set(1, new R(0, 1, 0x9999L));
        assertNotEquals(fold(store()), fold(changed));
    }

    @Test
    public void aMovedRegionMovesTheAggregate() {
        final List<R> moved = store();
        moved.set(0, new R(9, 9, 0x1111L));   // same token, somewhere else
        assertNotEquals(fold(store()), fold(moved));
    }

    @Test
    public void duplicateTokensDoNotCancel() {
        final List<R> twins = List.of(new R(0, 0, 0x1234L), new R(5, 5, 0x1234L));
        assertNotEquals("two regions with the same token are not an empty store", 0L, fold(twins));
    }

    @Test
    public void negativeCoordinatesDoNotAlias() {
        assertNotEquals(CsLodSummary.token(-1, -1, 7L), CsLodSummary.token(1, 1, 7L));
        assertNotEquals(CsLodSummary.token(-1, 1, 7L), CsLodSummary.token(1, -1, 7L));
        assertNotEquals(CsLodSummary.token(0, -1, 7L), CsLodSummary.token(-1, 0, 7L));
    }
}
