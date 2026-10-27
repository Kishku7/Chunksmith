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

import java.util.UUID;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The background-send radius (mod_support #45): a client is served what its renderer draws first, and
 * the radius only grows from there, one step per idle poll, up to a ceiling.
 */
public class CsLodBackfillTest {

    private static final int CAP = 16384;

    @Test
    public void aPlayerWhoHasNeverAdvancedGetsTheirOwnRadius() {
        CsLodBackfill backfill = new CsLodBackfill();

        assertEquals(1024, backfill.radius(UUID.randomUUID(), 1024));
    }

    // Immediate needs first: the first thing served is exactly the old behaviour, growth comes after.
    @Test
    public void growthDoublesFromTheirOwnRadiusUpToTheCeiling() {
        CsLodBackfill backfill = new CsLodBackfill();
        UUID player = UUID.randomUUID();

        assertEquals(2048, backfill.advance(player, 1024, CAP));
        assertEquals(4096, backfill.advance(player, 1024, CAP));
        assertEquals(8192, backfill.advance(player, 1024, CAP));
        assertEquals(16384, backfill.advance(player, 1024, CAP));
        assertEquals(16384, backfill.advance(player, 1024, CAP));
        assertEquals(16384, backfill.radius(player, 1024));
    }

    @Test
    public void theGrownRadiusIsWhatTheSummaryAndTheIndexBothSee() {
        CsLodBackfill backfill = new CsLodBackfill();
        UUID player = UUID.randomUUID();
        backfill.advance(player, 256, CAP);

        assertEquals(512, backfill.radius(player, 256));
        assertEquals(512, backfill.radius(player, 256));
    }

    // A repeat hello (token renewal) re-announces the same radius; that must not shrink what was grown.
    @Test
    public void aRepeatHelloDoesNotShrinkTheRadius() {
        CsLodBackfill backfill = new CsLodBackfill();
        UUID player = UUID.randomUUID();
        backfill.advance(player, 256, CAP);
        backfill.advance(player, 256, CAP);

        assertEquals(1024, backfill.radius(player, 256));
    }

    @Test
    public void aClientThatDrawsMoreThanWeHaveGrownIsStillServedWhatItDraws() {
        CsLodBackfill backfill = new CsLodBackfill();
        UUID player = UUID.randomUUID();
        backfill.advance(player, 256, CAP);

        assertEquals(4096, backfill.radius(player, 4096));
    }

    @Test
    public void aPlayerWhoLeavesStartsOverAtTheirOwnRadius() {
        CsLodBackfill backfill = new CsLodBackfill();
        UUID player = UUID.randomUUID();
        backfill.advance(player, 256, CAP);
        backfill.forget(player);

        assertEquals(256, backfill.radius(player, 256));
    }

    @Test
    public void aBaseAlreadyAtTheCeilingStaysThere() {
        assertEquals(CAP, CsLodBackfill.next(CAP, CAP));
        assertEquals(CAP, CsLodBackfill.next(12000, CAP));
    }

    @Test
    public void eachPlayerGrowsIndependently() {
        CsLodBackfill backfill = new CsLodBackfill();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        backfill.advance(alice, 256, CAP);
        backfill.advance(alice, 256, CAP);

        assertEquals(1024, backfill.radius(alice, 256));
        assertEquals(256, backfill.radius(bob, 256));
    }
}
