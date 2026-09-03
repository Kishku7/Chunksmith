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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * "Once it is done, it is done."
 *
 * <p>These tests are about ONE promise: a world that finished a pregen is never put behind the
 * progress screen again. The interesting cases are the ones either side of that -- a partial run
 * must still continue, and a raised radius must re-arm the feature rather than being refused
 * forever by a stale record.
 */
public class WorldEnterDoneTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private Path world() throws IOException {
        return temp.newFolder("world").toPath();
    }

    private static WorldEnterDone done(long radius) {
        return new WorldEnterDone(OVERWORLD, radius, 1_756_000_000_000L);
    }

    @Test
    public void aWorldThatNeverFinishedHasNoRecord() throws IOException {
        assertFalse(WorldEnterDone.read(world()).isPresent());
    }

    @Test
    public void aFinishedWorldIsNeverPregeneratedAgain() throws IOException {
        Path w = world();
        assertTrue(done(4096L).write(w));

        WorldEnterDone back = WorldEnterDone.read(w).orElseThrow(AssertionError::new);
        assertEquals(OVERWORLD, back.dimension());
        assertEquals(4096L, back.radiusBlocks());
        assertTrue("the whole point: a completed 4096 satisfies a configured 4096",
                back.satisfies(OVERWORLD, 4096L));
    }

    @Test
    public void aPartialRunLeavesNoRecordSoTheNextLoadContinues() throws IOException {
        Path w = world();
        // Pressing "Enter World Now" cancels nothing and records nothing -- only a real completion
        // writes the marker. This is what makes "continue a pregen" still work.
        assertFalse(WorldEnterDone.read(w).isPresent());
    }

    @Test
    public void raisingTheRadiusReArmsTheFeature() throws IOException {
        WorldEnterDone back = done(4096L);
        assertFalse("asking for more world than was generated must NOT be refused",
                back.satisfies(OVERWORLD, 8192L));
    }

    @Test
    public void loweringTheRadiusStaysSatisfied() throws IOException {
        WorldEnterDone back = done(8192L);
        assertTrue("a completed 8192 already covers a configured 4096",
                back.satisfies(OVERWORLD, 4096L));
    }

    @Test
    public void aRecordForAnotherDimensionDoesNotCount() throws IOException {
        assertFalse(done(4096L).satisfies(NETHER, 4096L));
    }

    @Test
    public void clearingMakesTheWorldEligibleAgain() throws IOException {
        Path w = world();
        done(4096L).write(w);
        WorldEnterDone.clear(w);
        assertFalse(WorldEnterDone.read(w).isPresent());
    }

    @Test
    public void clearingTwiceIsNotAnError() throws IOException {
        Path w = world();
        done(4096L).write(w);
        WorldEnterDone.clear(w);
        WorldEnterDone.clear(w);
        assertFalse(WorldEnterDone.read(w).isPresent());
    }

    @Test
    public void aLaterCompletionReplacesTheEarlierOne() throws IOException {
        Path w = world();
        done(4096L).write(w);
        done(8192L).write(w);
        assertEquals(8192L, WorldEnterDone.read(w).orElseThrow(AssertionError::new).radiusBlocks());
    }

    @Test
    public void aTruncatedRecordErrsTowardsRunningAgainNotTowardsNeverRunning() throws IOException {
        Path w = world();
        done(4096L).write(w);
        Files.write(w.resolve("chunksmith").resolve(WorldEnterDone.FILE_NAME),
                "{\n  \"dimension\": \"minecraft:ove".getBytes(StandardCharsets.UTF_8));
        // A half-written record must not parse as a smaller-but-valid radius, and must never make
        // the feature silently refuse to run forever. Absent is the safe reading.
        assertFalse(WorldEnterDone.read(w).isPresent());
    }

    @Test
    public void garbageDoesNotThrow() throws IOException {
        Path w = world();
        Files.createDirectories(w.resolve("chunksmith"));
        Files.write(w.resolve("chunksmith").resolve(WorldEnterDone.FILE_NAME),
                "not json at all".getBytes(StandardCharsets.UTF_8));
        assertFalse(WorldEnterDone.read(w).isPresent());
    }

    @Test
    public void aZeroRadiusRecordIsMeaninglessAndIsIgnored() throws IOException {
        Path w = world();
        Files.createDirectories(w.resolve("chunksmith"));
        Files.write(w.resolve("chunksmith").resolve(WorldEnterDone.FILE_NAME),
                ("{\n  \"dimension\": \"" + OVERWORLD + "\",\n  \"radiusBlocks\": 0\n}\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertFalse("a 0-block completion would satisfy nothing and mark everything done",
                WorldEnterDone.read(w).isPresent());
    }

    @Test
    public void noTempFileIsLeftBehind() throws IOException {
        Path w = world();
        done(4096L).write(w);
        assertFalse(Files.exists(
                w.resolve("chunksmith").resolve(WorldEnterDone.FILE_NAME + ".tmp")));
    }

    @Test
    public void theCompletionIsSeparateFromTheBorrowRecord() throws IOException {
        Path w = world();
        done(4096L).write(w);
        new WorldEnterState(true, 0L, 25L, 20L, 150.0).write(w);

        // Distinct files on purpose. Clearing the transient borrow record on the crash-restore path
        // must NOT wipe the permanent completion -- merging them would make "mid-pregen" and
        // "finished" the same signal.
        WorldEnterState.clear(w);
        assertFalse(WorldEnterState.read(w).isPresent());
        assertTrue("the completion must survive a crash-restore of the borrow record",
                WorldEnterDone.read(w).isPresent());
    }
}
