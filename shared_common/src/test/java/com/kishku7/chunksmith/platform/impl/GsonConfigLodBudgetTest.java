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

package com.kishku7.chunksmith.platform.impl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The LOD index budget, and specifically that its default is 2048 MB.
 *
 * <p>The number is pinned by a test because it is the one thing about
 * mod_support #23 that must NOT change. Making the budget configurable was the
 * fix; moving the default would have been a second, silent change of behaviour
 * on every server that upgraded, and an operator whose players suddenly saw more
 * or less terrain would have no way to connect that to a release note. If this
 * test fails, someone changed what an untouched server does.
 */
public class GsonConfigLodBudgetTest {

    /** What the constant compiled into every release before 3.16.0 was worth, in megabytes. */
    private static final long LEGACY_BUDGET_MB = 2L * 1024L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path configPath() throws IOException {
        return folder.newFolder("chunksmith").toPath().resolve("config.json");
    }

    @Test
    public void theDefaultIsTheOldHardCodedCeiling() throws IOException {
        GsonConfig config = new GsonConfig(configPath());
        assertEquals("upgrading must not change what an untouched server serves",
                LEGACY_BUDGET_MB, config.getLodIndexBudgetMb());
    }

    @Test
    public void zeroMeansNoCeilingRatherThanNoBytes() throws IOException {
        GsonConfig config = new GsonConfig(configPath());
        config.setLodIndexBudgetMb(0L);
        assertEquals(0L, config.getLodIndexBudgetMb());
    }

    @Test
    public void aRaisedBudgetSurvivesAReload() throws IOException {
        Path path = configPath();
        GsonConfig config = new GsonConfig(path);
        config.setLodIndexBudgetMb(8192L);
        // The point of the key is the operator who cannot casually restart, so it has to persist.
        assertEquals(8192L, new GsonConfig(path).getLodIndexBudgetMb());
    }

    @Test
    public void nonsenseFallsBackToNoCeilingRatherThanClamping() throws IOException {
        GsonConfig config = new GsonConfig(configPath());
        config.setLodIndexBudgetMb(-5L);
        assertEquals(0L, config.getLodIndexBudgetMb());
        // Above the sanity ceiling is a typo, not a request, and clamping it to the ceiling would
        // answer "done" with a number nobody asked for.
        config.setLodIndexBudgetMb(Long.MAX_VALUE);
        assertEquals(0L, config.getLodIndexBudgetMb());
    }

    @Test
    public void aFreshConfigCarriesTheKey() throws IOException {
        Path path = configPath();
        new GsonConfig(path);
        assertTrue("lodIndexBudgetMb missing from a freshly written config",
                Files.readString(path).contains("lodIndexBudgetMb"));
    }
}
