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

    /** The knee, measured on an 8-core server and again on a 2-core one. See GsonConfig. */
    private static final long KNEE = 200L;

    @Test
    public void everyPlatformReadsOneDefault() {
        // The plugin had its OWN hard-coded 50 while the mod scaled by core count, so a Paper
        // server never saw any of the tuning -- one setting with two registries, only one of them
        // maintained. Both now read Config.DISPATCH_MAX_CONCURRENT_DEFAULT. SCOPE, stated plainly:
        // this pins the shared constant and the mod's use of it. BukkitConfig lives in another
        // module with no test source set, so nothing here can prove the plugin reads it -- what
        // prevents drift there is that the literal is gone, not this assertion.
        assertEquals(KNEE, com.kishku7.chunksmith.platform.Config.DISPATCH_MAX_CONCURRENT_DEFAULT);
    }

    @Test
    public void theDefaultIsTheMeasuredKnee() throws IOException {
        ServerEnvironment.setDedicated(true);
        assertEquals("the knee was measured at 200 on both an 8-core and a 2-core server",
                KNEE, new GsonConfig(configPath()).getDispatchMaxConcurrent());
    }

    @Test
    public void theDefaultDoesNotScaleWithCoreCount() throws IOException {
        // The old default was cores*25, which reads as protecting a small machine and actually
        // cost one 43 percent of its throughput: 50 measured 16.9 cps against 29.8 at 200 on the
        // same two cores. Width buys concurrency against per-chunk LATENCY, and latency does not
        // shrink when you remove cores. If this test ever fails because someone reintroduced a
        // per-core term, measure before believing it.
        long onThisMachine = new GsonConfig(configPath()).getDispatchMaxConcurrent();
        assertEquals("the default must not depend on availableProcessors()",
                KNEE, onThisMachine);
    }

    @Test
    public void aClientGetsTheSameDefaultAndThatIsMeasured() throws IOException {
        // Benched on a 4-core client with voxy and Sodium, 16640 chunks, arms interleaved:
        // width 200 took 275s and 298s with a worst pause of 6.0s; width 16 took 458s and 379s
        // with worst pauses of 20.9s and 16.4s. Narrowing lost 46 percent of throughput AND
        // tripled the worst stall, so there is no speed-for-smoothness trade to make here.
        ServerEnvironment.setDedicated(false);
        long client = new GsonConfig(configPath()).getDispatchMaxConcurrent();
        ServerEnvironment.setDedicated(true);
        long server = new GsonConfig(configPath()).getDispatchMaxConcurrent();
        assertEquals(server, client);
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
