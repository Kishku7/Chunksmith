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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The ticket-level sample runs when /cs debug asks for it and at no other time (4.4.1, mod_support
 * #44). Until then it ran on a ten-second timer inside the server tick, which is how a diagnostic
 * nobody was reading took down a game when another mod changed what it read.
 */
public class UnloadDiagnosticsTest {

    @Before
    public void reset() {
        UnloadDiagnostics.clear();
    }

    @After
    public void tearDown() {
        UnloadDiagnostics.clear();
    }

    @Test
    public void withNoSamplerTheLinesSayUnavailableRatherThanPending() {
        UnloadDiagnostics.sampleLevelsNow();
        assertEquals("unavailable on this platform", UnloadDiagnostics.describeLevels());
    }

    @Test
    public void offeringTheSamplerDoesNotRunIt() {
        AtomicInteger runs = new AtomicInteger();
        UnloadDiagnostics.offerLevelSampler(runs::incrementAndGet);
        assertEquals(0, runs.get());
        assertEquals("not sampled yet", UnloadDiagnostics.describeLevels());
    }

    @Test
    public void askingRunsItOncePerAsk() {
        AtomicInteger runs = new AtomicInteger();
        UnloadDiagnostics.offerLevelSampler(() -> {
            runs.incrementAndGet();
            UnloadDiagnostics.reportLevels(3L, 4L, 5L, System.currentTimeMillis());
        });
        UnloadDiagnostics.sampleLevelsNow();
        UnloadDiagnostics.sampleLevelsNow();
        assertEquals(2, runs.get());
        assertTrue(UnloadDiagnostics.describeLevels().startsWith("ticking=3 loaded=4 droppable=5"));
    }

    @Test
    public void stoppingTheServerDropsItsSampler() {
        AtomicInteger runs = new AtomicInteger();
        UnloadDiagnostics.offerLevelSampler(runs::incrementAndGet);
        UnloadDiagnostics.clear();
        UnloadDiagnostics.sampleLevelsNow();
        assertEquals(0, runs.get());
    }
}
