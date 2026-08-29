/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.lod.net;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The freshness token that replaced {@code crc.update(Files.readAllBytes(file))}. The contract the
 * client depends on is one sentence (a region that changed must produce a different token) and
 * everything below pins it.
 */
public class CsLodRegionHashTest {

    @Test
    public void anUnchangedRegionKeepsItsToken() {
        assertEquals(CsLodRegionHash.of(1_700_000_000_000L, 4_812_345L),
                CsLodRegionHash.of(1_700_000_000_000L, 4_812_345L));
    }

    @Test
    public void aNewMtimeMovesTheToken() {
        long size = 4_812_345L;
        assertNotEquals(CsLodRegionHash.of(1_700_000_000_000L, size),
                CsLodRegionHash.of(1_700_000_000_001L, size));
    }

    @Test
    public void aNewSizeMovesTheToken() {
        long mtime = 1_700_000_000_000L;
        assertNotEquals(CsLodRegionHash.of(mtime, 4_812_345L),
                CsLodRegionHash.of(mtime, 4_812_346L));
    }

    @Test
    public void mtimeAndSizeCannotCancel() {
        long mtime = 1_700_000_000_000L;
        long size = 5_000_000L;
        assertNotEquals("+1ms / -1 byte must not alias",
                CsLodRegionHash.of(mtime, size),
                CsLodRegionHash.of(mtime + 1, size - 1));
        assertNotEquals("+1ms / +1 byte must not alias",
                CsLodRegionHash.of(mtime, size),
                CsLodRegionHash.of(mtime + 1, size + 1));
        // And the transposition: mtime and size swapped are not the same region.
        assertNotEquals(CsLodRegionHash.of(4321L, 1234L), CsLodRegionHash.of(1234L, 4321L));
    }

    @Test
    public void aPregensWholeRunOfRegionsIsCollisionFree() {
        Set<Long> tokens = new HashSet<>();
        long mtime = 1_700_000_000_000L;
        long size = 1_000_000L;
        for (int i = 0; i < 3600; i++) {
            tokens.add(CsLodRegionHash.of(mtime, size));
            mtime += 1000L;
            size += 4_600L;   // ~4.6 KB/chunk, the measured store cost
        }
        assertEquals("3600 consecutive (mtime, size) pairs, no collisions", 3600, tokens.size());
    }

    @Test
    public void adjacentInputsScatter() {
        long a = CsLodRegionHash.of(1_700_000_000_000L, 4_000_000L);
        long b = CsLodRegionHash.of(1_700_000_000_001L, 4_000_000L);
        assertTrue("one millisecond apart must flip roughly half the bits, not one",
                Long.bitCount(a ^ b) > 16);
    }

    @Test
    public void zeroesAreNotSpecial() {
        assertNotEquals(CsLodRegionHash.of(0L, 0L), CsLodRegionHash.of(0L, 1L));
        assertNotEquals(CsLodRegionHash.of(0L, 0L), CsLodRegionHash.of(1L, 0L));
    }
}
