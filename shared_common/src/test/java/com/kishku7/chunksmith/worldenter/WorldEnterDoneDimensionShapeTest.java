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
 * The completion record now remembers its shape (mod_support #51) and there is one per dimension
 * (mod_support #52). The promise it keeps is unchanged: a finished run is never repeated, and a request
 * for something not yet generated is never refused.
 */
public class WorldEnterDoneDimensionShapeTest {

    private static final String O = WorldEnterDimensions.OVERWORLD_ID;
    private static final String N = WorldEnterDimensions.NETHER_ID;

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private Path world() throws IOException {
        return temp.newFolder("world").toPath();
    }

    @Test
    public void aFinishedCircleDoesNotSatisfyASquareOfTheSameSize() {
        WorldEnterDone circle = new WorldEnterDone(O, 4096L, 0.0, 0.0, "circle", 1L);

        assertTrue(circle.satisfies(O, 4096L, 0.0, 0.0, "circle"));
        assertFalse("the square's corners were never generated", circle.satisfies(O, 4096L, 0.0, 0.0, "square"));
    }

    @Test
    public void aFinishedSquareSatisfiesAnyShapeInsideIt() {
        WorldEnterDone square = new WorldEnterDone(O, 4096L, 0.0, 0.0, "square", 1L);

        assertTrue(square.satisfies(O, 4096L, 0.0, 0.0, "circle"));
        assertTrue(square.satisfies(O, 2048L, 0.0, 0.0, "star"));
        assertFalse(square.satisfies(O, 8192L, 0.0, 0.0, "circle"));
    }

    // Every record written before 4.8.0 is a circle, and the four-argument form still means a circle.
    @Test
    public void aRecordWithNoShapeIsACircle() throws IOException {
        Path w = world();
        Files.createDirectories(w.resolve("chunksmith"));
        Files.writeString(w.resolve("chunksmith").resolve("worldenter-done.json"),
                "{\n  \"dimension\": \"minecraft:overworld\",\n  \"radiusBlocks\": 4096,\n"
                        + "  \"completedAtMillis\": 1\n}\n", StandardCharsets.UTF_8);

        WorldEnterDone old = WorldEnterDone.read(w).orElseThrow(AssertionError::new);
        assertEquals("circle", old.shape());
        assertTrue(old.satisfies(O, 4096L, 0.0, 0.0));
        assertFalse(old.satisfies(O, 4096L, 0.0, 0.0, "square"));
    }

    @Test
    public void theShapeSurvivesAWriteAndARead() throws IOException {
        Path w = world();
        assertTrue(new WorldEnterDone(O, 2048L, 5.0, -7.0, "hexagon", 9L).write(w));

        WorldEnterDone back = WorldEnterDone.read(w).orElseThrow(AssertionError::new);
        assertEquals("hexagon", back.shape());
        assertEquals(2048L, back.radiusBlocks());
        assertEquals(5.0, back.centerX(), 0.0);
    }

    @Test
    public void eachDimensionHasItsOwnRecord() throws IOException {
        Path w = world();
        assertTrue(new WorldEnterDone(O, 4096L, 1L).write(w));

        assertTrue(WorldEnterDone.read(w, O).isPresent());
        assertFalse("finishing the overworld says nothing about the Nether", WorldEnterDone.read(w, N).isPresent());

        assertTrue(new WorldEnterDone(N, 1024L, 2L).write(w));
        assertEquals(1024L, WorldEnterDone.read(w, N).orElseThrow(AssertionError::new).radiusBlocks());
        assertEquals(4096L, WorldEnterDone.read(w, O).orElseThrow(AssertionError::new).radiusBlocks());
        assertTrue(Files.isRegularFile(w.resolve("chunksmith").resolve("worldenter-done-nether.json")));
        assertTrue(Files.isRegularFile(w.resolve("chunksmith").resolve("worldenter-done.json")));
    }

    @Test
    public void clearingRemovesEveryDimensionsRecord() throws IOException {
        Path w = world();
        new WorldEnterDone(O, 4096L, 1L).write(w);
        new WorldEnterDone(N, 4096L, 1L).write(w);

        WorldEnterDone.clear(w);

        assertFalse(WorldEnterDone.read(w, O).isPresent());
        assertFalse(WorldEnterDone.read(w, N).isPresent());
    }
}
