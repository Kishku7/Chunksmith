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

package com.kishku7.chunksmith.lod.client;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The sync interval, its default, and the clamp.
 *
 * <p>The floor is enforced in code, not in the file, and that is
 * the point of these tests. A config value is a suggestion from
 * whoever last edited the file; {@code sync-interval-seconds=1}
 * must not be able to turn the self-healing sync into a poll
 * storm against a server that is trying to run a pregen, which is
 * the exact class of problem this release exists to fix.
 */
public class CsLodClientConfigTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void theDefaultIsFiveMinutes() {
        assertEquals(300, CsLodClientConfig.DEFAULT_SYNC_SECONDS);
    }

    @Test
    public void theFloorIsThirtySeconds() {
        assertEquals(30, CsLodClientConfig.MIN_SYNC_SECONDS);
    }

    @Test
    public void clampRaisesToFloor() {
        assertEquals(30, CsLodClientConfig.clamp(29));
        assertEquals(30, CsLodClientConfig.clamp(10));
        assertEquals(30, CsLodClientConfig.clamp(1));
        assertEquals(30, CsLodClientConfig.clamp(0));
        assertEquals("a negative interval", 30, CsLodClientConfig.clamp(-600));
        assertEquals(30, CsLodClientConfig.clamp(Integer.MIN_VALUE));
    }

    @Test
    public void clampLeavesLegalValues() {
        assertEquals(30, CsLodClientConfig.clamp(30));
        assertEquals(31, CsLodClientConfig.clamp(31));
        assertEquals(300, CsLodClientConfig.clamp(300));
        assertEquals(86_400, CsLodClientConfig.clamp(86_400));
        assertEquals("there is no ceiling",
                Integer.MAX_VALUE, CsLodClientConfig.clamp(Integer.MAX_VALUE));
    }

    @Test
    public void aMissingConfigIsWritten() throws IOException {
        Path dir = temp.newFolder("config").toPath();

        String said = CsLodClientConfig.load(dir);

        assertEquals(300, CsLodClientConfig.syncIntervalSeconds());
        assertEquals(300_000L, CsLodClientConfig.syncIntervalMillis());
        assertTrue(CsLodClientConfig.isLoaded());
        assertTrue(said, said.contains("wrote"));
        assertTrue("config file was not written",
                Files.isRegularFile(dir.resolve(CsLodClientConfig.FILE_NAME)));
    }

    @Test
    public void aConfiguredValueIsClamped() throws IOException {
        Path dir = write("sync-interval-seconds=5");

        String said = CsLodClientConfig.load(dir);

        assertEquals("5 seconds is not honoured", 30, CsLodClientConfig.syncIntervalSeconds());
        assertEquals(30_000L, CsLodClientConfig.syncIntervalMillis());
        assertTrue(said, said.contains("minimum"));
    }

    @Test
    public void aLegalValueSticks() throws IOException {
        Path dir = write("sync-interval-seconds=45");

        CsLodClientConfig.load(dir);

        assertEquals(45, CsLodClientConfig.syncIntervalSeconds());
        assertEquals(45_000L, CsLodClientConfig.syncIntervalMillis());
    }

    @Test
    public void garbageFallsBackToTheDefault() throws IOException {
        Path dir = write("sync-interval-seconds=soon");

        String said = CsLodClientConfig.load(dir);

        assertEquals(300, CsLodClientConfig.syncIntervalSeconds());
        assertTrue(said, said.contains("not a number"));
    }

    @Test
    public void aMissingKeyFallsBackToTheDefault() throws IOException {
        Path dir = write("something-else=1");

        CsLodClientConfig.load(dir);

        assertEquals(300, CsLodClientConfig.syncIntervalSeconds());
    }

    @Test
    public void theAccessorsCanNeverReturnLessThanTheFloor() throws IOException {
        for (String value : new String[]{"0", "1", "-1", "-2147483648", "29"}) {
            CsLodClientConfig.load(write("sync-interval-seconds=" + value));
            assertTrue("interval=" + value,
                    CsLodClientConfig.syncIntervalSeconds() >= CsLodClientConfig.MIN_SYNC_SECONDS);
            assertTrue("interval=" + value,
                    CsLodClientConfig.syncIntervalMillis() >= CsLodClientConfig.MIN_SYNC_SECONDS * 1000L);
        }
    }

    private Path write(String line) throws IOException {
        Path dir = temp.newFolder().toPath();
        Files.write(dir.resolve(CsLodClientConfig.FILE_NAME),
                line.getBytes(StandardCharsets.US_ASCII));
        return dir;
    }
}
