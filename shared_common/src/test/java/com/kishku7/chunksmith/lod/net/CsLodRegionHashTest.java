package com.kishku7.chunksmith.lod.net;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The freshness token that replaced {@code crc.update(Files.readAllBytes(file))}.
 *
 * <p>The contract the client depends on is exactly one sentence: <b>a region that changed must produce a
 * different token.</b> Everything below is that sentence, pinned.
 */
public class CsLodRegionHashTest {

    /** The same region, unchanged, is the same token. Otherwise every re-join re-downloads the world. */
    @Test
    public void anUnchangedRegionKeepsItsToken() {
        assertEquals(CsLodRegionHash.of(1_700_000_000_000L, 4_812_345L),
                CsLodRegionHash.of(1_700_000_000_000L, 4_812_345L));
    }

    /** A rewritten region has a new mtime. That alone must move the token. */
    @Test
    public void aNewMtimeMovesTheToken() {
        final long size = 4_812_345L;
        assertNotEquals(CsLodRegionHash.of(1_700_000_000_000L, size),
                CsLodRegionHash.of(1_700_000_000_001L, size));
    }

    /** A grown region has a new size -- the pregen's normal case. That alone must move the token too. */
    @Test
    public void aNewSizeMovesTheToken() {
        final long mtime = 1_700_000_000_000L;
        assertNotEquals(CsLodRegionHash.of(mtime, 4_812_345L),
                CsLodRegionHash.of(mtime, 4_812_346L));
    }

    /**
     * The two fields must not be able to cancel each other out.
     *
     * <p>A naive {@code mtime ^ size} lets a region written one millisecond later and one byte shorter land
     * on the token of the region it replaced -- and the client would keep the stale copy forever, with no
     * mechanism that could ever correct it. Every field goes through the avalanche for this reason.
     */
    @Test
    public void mtimeAndSizeCannotCancel() {
        final long mtime = 1_700_000_000_000L;
        final long size = 5_000_000L;
        assertNotEquals("+1ms / -1 byte must not alias",
                CsLodRegionHash.of(mtime, size),
                CsLodRegionHash.of(mtime + 1, size - 1));
        assertNotEquals("+1ms / +1 byte must not alias",
                CsLodRegionHash.of(mtime, size),
                CsLodRegionHash.of(mtime + 1, size + 1));
        // And the transposition: mtime and size swapped are not the same region.
        assertNotEquals(CsLodRegionHash.of(4321L, 1234L), CsLodRegionHash.of(1234L, 4321L));
    }

    /**
     * The realistic worst case: a pregen writing one region per second, each a little bigger than the last,
     * for an hour. Every single one must be distinguishable from every other.
     */
    @Test
    public void aPregensWholeRunOfRegionsIsCollisionFree() {
        final Set<Long> tokens = new HashSet<>();
        long mtime = 1_700_000_000_000L;
        long size = 1_000_000L;
        for (int i = 0; i < 3600; i++) {
            tokens.add(CsLodRegionHash.of(mtime, size));
            mtime += 1000L;
            size += 4_600L;   // ~4.6 KB/chunk, the measured store cost
        }
        assertEquals("3600 consecutive (mtime, size) pairs, no collisions", 3600, tokens.size());
    }

    /** Adjacent milliseconds at an identical size must avalanche, not sit next to each other. */
    @Test
    public void adjacentInputsProduceUnrelatedTokens() {
        final long a = CsLodRegionHash.of(1_700_000_000_000L, 4_000_000L);
        final long b = CsLodRegionHash.of(1_700_000_000_001L, 4_000_000L);
        assertTrue("one millisecond apart must flip roughly half the bits, not one",
                Long.bitCount(a ^ b) > 16);
    }

    /** Degenerate inputs must not be special-cased into a shared value. */
    @Test
    public void zeroesAreNotSpecial() {
        assertNotEquals(CsLodRegionHash.of(0L, 0L), CsLodRegionHash.of(0L, 1L));
        assertNotEquals(CsLodRegionHash.of(0L, 0L), CsLodRegionHash.of(1L, 0L));
    }
}
