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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** worldEnterPregenShape (#51) and worldEnterPregenDimensions (#52): defaults, canonical form, bad values. */
public class GsonConfigWorldEnterShapeDimensionsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path configPath() throws IOException {
        return folder.newFolder("chunksmith").toPath().resolve("config.json");
    }

    // A config written before 4.8.0 has neither key, and must behave exactly as it did: a circle, overworld only.
    @Test
    public void anInstallFromBefore4_8_0KeepsTheOldBehaviour() throws IOException {
        Path path = configPath();
        Files.writeString(path, "{\"worldEnterPregenRadius\": 4096}", StandardCharsets.UTF_8);
        GsonConfig config = new GsonConfig(path);

        assertEquals("circle", config.getWorldEnterPregenShape());
        assertEquals("overworld", config.getWorldEnterPregenDimensions());
    }

    @Test
    public void aNewInstallDefaultsToACircleInTheOverworld() throws IOException {
        GsonConfig config = new GsonConfig(configPath());

        assertEquals("circle", config.getWorldEnterPregenShape());
        assertEquals("overworld", config.getWorldEnterPregenDimensions());
    }

    @Test
    public void theFileHoldsTheCanonicalForm() throws IOException {
        Path path = configPath();
        GsonConfig config = new GsonConfig(path);
        config.setWorldEnterPregenShape("  SQUARE ");
        config.setWorldEnterPregenDimensions("end, Overworld");

        String saved = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"worldEnterPregenShape\": \"square\""));
        assertTrue(saved.contains("\"worldEnterPregenDimensions\": \"overworld,end\""));
        assertEquals("square", new GsonConfig(path).getWorldEnterPregenShape());
        assertEquals("overworld,end", new GsonConfig(path).getWorldEnterPregenDimensions());
    }

    @Test
    public void allThreeIsSavedAsAll() throws IOException {
        GsonConfig config = new GsonConfig(configPath());
        config.setWorldEnterPregenDimensions("nether,end,overworld");

        assertEquals("all", config.getWorldEnterPregenDimensions());
    }

    // A hand-edited bad value falls back to the default rather than silently running something else.
    @Test
    public void aBadValueInTheFileFallsBackToTheDefault() throws IOException {
        Path path = configPath();
        Files.writeString(path,
                "{\"worldEnterPregenShape\": \"blob\", \"worldEnterPregenDimensions\": \"moon\"}",
                StandardCharsets.UTF_8);
        GsonConfig config = new GsonConfig(path);

        assertEquals("circle", config.getWorldEnterPregenShape());
        assertEquals("overworld", config.getWorldEnterPregenDimensions());
    }
}
