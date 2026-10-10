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

import com.kishku7.chunksmith.lod.CompositeLodSink;
import com.kishku7.chunksmith.lod.LodSink;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When the server tells connected players the store grew (mod_support #45). Two failures matter:
 * pushing forever after a pregen ends (every connected client re-asking every minute for nothing),
 * and never pushing the last regions, which only settle ten seconds after the final write.
 */
public class CsLodGrowthTest {

    private static final long T0 = 1_000_000L;
    private static final long MINUTE = CsLodGrowth.PUSH_INTERVAL_MILLIS;

    @Before
    public void reset() {
        CsLodGrowth.reset();
    }

    @After
    public void tearDown() {
        CsLodGrowth.reset();
    }

    @Test
    public void aStoreNobodyWritesIsNeverPushed() {
        assertFalse(CsLodGrowth.pushDue(T0));
        assertFalse(CsLodGrowth.pushDue(T0 + 10 * MINUTE));
    }

    @Test
    public void aWritePushesAndThenWaitsTheInterval() {
        CsLodGrowth.noteWrite(T0);
        assertTrue(CsLodGrowth.pushDue(T0 + 1_000L));
        CsLodGrowth.noteWrite(T0 + 2_000L);
        assertFalse("never twice inside one interval", CsLodGrowth.pushDue(T0 + 30_000L));
        assertTrue(CsLodGrowth.pushDue(T0 + 1_000L + MINUTE));
    }

    @Test
    public void theLastRegionsArePushedOnceTheySettleAndThenItStops() {
        // A pregen writes until T0, and we happen to push one second later: that region has not settled,
        // so this push could not include it.
        CsLodGrowth.noteWrite(T0);
        assertTrue(CsLodGrowth.pushDue(T0 + 1_000L));
        // So the next interval must push again, even with no new write...
        assertTrue(CsLodGrowth.pushDue(T0 + 1_000L + MINUTE));
        // ...and after that, with everything settled and covered, never again.
        assertFalse(CsLodGrowth.pushDue(T0 + 1_000L + 2 * MINUTE));
        assertFalse(CsLodGrowth.pushDue(T0 + 1_000L + 10 * MINUTE));
    }

    @Test
    public void aWriteAfterTheLastPushWakesItAgain() {
        CsLodGrowth.noteWrite(T0);
        assertTrue(CsLodGrowth.pushDue(T0 + 20_000L));
        assertFalse(CsLodGrowth.pushDue(T0 + 20_000L + 5 * MINUTE));
        CsLodGrowth.noteWrite(T0 + 6 * MINUTE);
        assertTrue(CsLodGrowth.pushDue(T0 + 6 * MINUTE + 1_000L));
    }

    @Test
    public void aCompositeFeedsDirectlyWhenAnyOfItsSinksDoes() {
        LodSink direct = new LodSink() {
            @Override
            public boolean offer(Object chunk) {
                return true;
            }

            @Override
            public int queueDepth() {
                return 0;
            }

            @Override
            public boolean feedsRendererDirectly() {
                return true;
            }
        };
        assertFalse(LodSink.NOOP.feedsRendererDirectly());
        assertFalse(new CompositeLodSink(List.of(LodSink.NOOP)).feedsRendererDirectly());
        assertTrue(new CompositeLodSink(List.of(LodSink.NOOP, direct)).feedsRendererDirectly());
    }
}
