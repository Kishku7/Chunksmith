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

import com.kishku7.chunksmith.platform.ServerEnvironment;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The dispatch-width default, and that it knows what kind of machine it is on (mod_support #33).
 *
 * <p>SCOPE: these cases pin the CURVE -- the arithmetic that turns a processor count and a server
 * kind into a ceiling. They say nothing about whether the integrated numbers are right on real
 * hardware; those are unmeasured, and the two figures asked of the reporter on #33 are what should
 * replace them.
 */
public class GsonConfigDispatchWidthTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void restoreEnvironment() {
        // Static and process-wide: leaking "client" into another test would quietly move its numbers.
        ServerEnvironment.setDedicated(true);
    }

    private int folderSeq;

    private Path configPath() throws IOException {
        // A fresh directory per call: two configs in one test case must not collide, and a default
        // only counts as a default when it is read from a config nobody has written to yet.
        return folder.newFolder("chunksmith" + folderSeq++).toPath().resolve("config.json");
    }

    private static int cores() {
        return Runtime.getRuntime().availableProcessors();
    }

    @Test
    public void aDedicatedServerKeepsTheMeasuredServerCurve() throws IOException {
        ServerEnvironment.setDedicated(true);
        long expected = Math.min(400L, Math.max(8L, cores() * 25L));
        assertEquals("upgrading must not change what an untouched dedicated server does",
                expected, new GsonConfig(configPath()).getDispatchMaxConcurrent());
    }

    @Test
    public void aClientGetsAMuchNarrowerPipelineThanAServerWithTheSameCores() throws IOException {
        ServerEnvironment.setDedicated(false);
        long client = new GsonConfig(configPath()).getDispatchMaxConcurrent();
        ServerEnvironment.setDedicated(true);
        long server = new GsonConfig(configPath()).getDispatchMaxConcurrent();
        assertTrue("a client shares its cores with the renderer and the game; " + client
                + " should be well under " + server, client < server);
    }

    @Test
    public void theOldFloorOfFiftyNoLongerHoldsTheCurveUp() throws IOException {
        // The floor was the reason the scaling could never descend for the machine that needed it.
        ServerEnvironment.setDedicated(false);
        long width = new GsonConfig(configPath()).getDispatchMaxConcurrent();
        assertTrue("a client on this box got " + width + ", which the old floor of 50 forbade",
                width < 50L || cores() > 8);
    }

    @Test
    public void anOperatorsOwnValueStillWinsAndSurvivesAReload() throws IOException {
        Path path = configPath();
        GsonConfig config = new GsonConfig(path);
        config.setDispatchMaxConcurrent(16L);
        assertEquals(16L, config.getDispatchMaxConcurrent());
        assertEquals("the reporter's hand-tuned value must not be overwritten by the new curve",
                16L, new GsonConfig(path).getDispatchMaxConcurrent());
    }

    @Test
    public void aFreshConfigCarriesTheKey() throws IOException {
        Path path = configPath();
        new GsonConfig(path);
        assertTrue("dispatchMaxConcurrent missing from a freshly written config",
                Files.readString(path).contains("dispatchMaxConcurrent"));
    }
}
