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

package com.kishku7.chunksmith.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The frozen-world ticket purge must keep chunks while there is room, purge every tick once the
 * cache is full, and not flap at the boundary (issue #37).
 */
public class FrozenTicketPurgeTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    public void capScalesWithTheHeap() {
        assertEquals(11468, FrozenTicketPurge.capFor(4 * GB));
        assertEquals(45875, FrozenTicketPurge.capFor(16 * GB));
        assertEquals(0, FrozenTicketPurge.capFor(-1L));
    }

    @Test
    public void effectiveCapIsTheHeapCapWithoutTheHarnessOverride() {
        // The override is a -D the harness sets; unit tests never do.
        assertEquals(FrozenTicketPurge.capFor(16 * GB), FrozenTicketPurge.effectiveCap(16 * GB));
    }

    @Test
    public void keepsChunksBelowTheCap() {
        FrozenTicketPurge p = new FrozenTicketPurge();
        int cap = FrozenTicketPurge.capFor(16 * GB);
        for (int r = 0; r <= cap; r += 97) {
            assertFalse(p.tick(r, 16 * GB));
        }
        assertFalse(p.tick(cap, 16 * GB));
        assertEquals(0, p.switchesOn());
    }

    @Test
    public void purgesEveryTickAboveTheCapUntilResumeFraction() {
        FrozenTicketPurge p = new FrozenTicketPurge();
        int cap = FrozenTicketPurge.capFor(16 * GB);
        int resume = (int) (cap * FrozenTicketPurge.RESUME_FRACTION);
        assertTrue(p.tick(cap + 1, 16 * GB));
        assertTrue(p.tick(cap - 1, 16 * GB));      // under the cap but above resume: keep purging
        assertTrue(p.tick(resume + 1, 16 * GB));
        assertFalse(p.tick(resume, 16 * GB));
        assertFalse(p.tick(cap, 16 * GB));         // refilling: no purge until over the cap again
        assertTrue(p.tick(cap + 1, 16 * GB));
        assertEquals(2, p.switchesOn());
    }

    @Test
    public void smallHeapIsEffectivelyAlwaysPurging() {
        FrozenTicketPurge p = new FrozenTicketPurge();
        // ~13,000 resident is the floor the in-flight work keeps at 4 GB: above the cap.
        for (int i = 0; i < 1000; i++) {
            assertTrue(p.tick(13_000, 4 * GB));
        }
        assertEquals(1, p.switchesOn());
    }

    @Test
    public void resetStartsCachingAgain() {
        FrozenTicketPurge p = new FrozenTicketPurge();
        assertTrue(p.tick(50_000, 16 * GB));
        p.reset();
        assertEquals(0, p.switchesOn());
        assertFalse(p.tick(40_000, 16 * GB));
    }
}