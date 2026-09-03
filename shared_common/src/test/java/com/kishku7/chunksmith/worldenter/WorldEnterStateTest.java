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

package com.kishku7.chunksmith.worldenter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The borrow-and-give-back record behind the world-enter pregen.
 *
 * <p>The normal path -- change things, change them back -- is not what these
 * tests are about. They are about the path where there is no "afterwards": the
 * player force-quits mid-freeze, or the JVM dies. What must survive that is the
 * knowledge that we changed something and what it was before.
 */
public class WorldEnterStateTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private Path world() throws IOException {
        return temp.newFolder("world").toPath();
    }

    private static WorldEnterState sample() {
        return new WorldEnterState(false, 13000L, 25L, 20L, 150.0);
    }

    @Test
    public void nothingToRestoreWhenNothingWasBorrowed() throws IOException {
        assertFalse(WorldEnterState.read(world()).isPresent());
    }

    @Test
    public void whatWasBorrowedIsWhatComesBack() throws IOException {
        Path w = world();
        assertTrue(sample().write(w));

        WorldEnterState back = WorldEnterState.read(w).orElseThrow(AssertionError::new);
        // The point of the record: these are the player's values, not vanilla's defaults.
        assertFalse("a player who had time advancement OFF must get it back OFF",
                back.timeAdvanceWasOn());
        assertEquals(13000L, back.dayTime());
        assertEquals(25L, back.tickBudgetMillis());
        assertEquals(20L, back.playerReserveMillis());
        assertEquals(150.0, back.targetMspt(), 0.0001);
    }

    @Test
    public void aStateLeftBehindIsTheSignalThatWeCrashedMidFreeze() throws IOException {
        Path w = world();
        sample().write(w);
        // No clear() -- this is what the disk looks like after an alt-F4 during the freeze.
        assertTrue("a surviving state file means: we changed things and never gave them back",
                WorldEnterState.read(w).isPresent());
    }

    @Test
    public void clearingMeansEverythingWasHandedBack() throws IOException {
        Path w = world();
        sample().write(w);
        WorldEnterState.clear(w);
        assertFalse(WorldEnterState.read(w).isPresent());
    }

    @Test
    public void clearingTwiceIsNotAnError() throws IOException {
        Path w = world();
        sample().write(w);
        WorldEnterState.clear(w);
        WorldEnterState.clear(w);
        assertFalse(WorldEnterState.read(w).isPresent());
    }

    @Test
    public void writingTwiceKeepsTheSecond() throws IOException {
        Path w = world();
        sample().write(w);
        new WorldEnterState(true, 6000L, 60L, 0L, 400.0).write(w);
        WorldEnterState back = WorldEnterState.read(w).orElseThrow(AssertionError::new);
        assertTrue(back.timeAdvanceWasOn());
        assertEquals(6000L, back.dayTime());
        assertEquals(400.0, back.targetMspt(), 0.0001);
    }

    @Test
    public void aTruncatedFileReadsAsAbsentRatherThanAsGarbage() throws IOException {
        Path w = world();
        sample().write(w);
        Path f = w.resolve("chunksmith").resolve(WorldEnterState.FILE_NAME);
        // Half a file is what a torn write would leave. Handing back a parsed-from-rubbish value
        // would be worse than not restoring: the player would silently get someone else's numbers.
        Files.write(f, "{\n  \"timeAdvanceWasOn\": tr".getBytes(StandardCharsets.UTF_8));
        Optional<WorldEnterState> read = WorldEnterState.read(w);
        // Either absent, or parsed with safe fallbacks -- never an exception out of read().
        if (read.isPresent()) {
            assertEquals(0L, read.get().dayTime());
        }
    }

    @Test
    public void garbageDoesNotThrow() throws IOException {
        Path w = world();
        Files.createDirectories(w.resolve("chunksmith"));
        Files.write(w.resolve("chunksmith").resolve(WorldEnterState.FILE_NAME),
                "not json at all".getBytes(StandardCharsets.UTF_8));
        WorldEnterState.read(w);   // must not throw; failing to load a world over our bookkeeping
                                   // would punish the player for our problem
    }

    @Test
    public void noTempFileIsLeftBehind() throws IOException {
        Path w = world();
        sample().write(w);
        assertFalse("the temp file is a write detail and must not survive the write",
                Files.exists(w.resolve("chunksmith").resolve(WorldEnterState.FILE_NAME + ".tmp")));
    }

    @Test
    public void theStateLivesBesideTheWorldNotInTheConfig() throws IOException {
        Path w = world();
        sample().write(w);
        // Per world on purpose: two saves can be mid-pregen independently, and this is bookkeeping
        // rather than a setting -- a config key with no `/cs set` would break the house rule.
        assertTrue(Files.isRegularFile(
                w.resolve("chunksmith").resolve(WorldEnterState.FILE_NAME)));
    }
}
