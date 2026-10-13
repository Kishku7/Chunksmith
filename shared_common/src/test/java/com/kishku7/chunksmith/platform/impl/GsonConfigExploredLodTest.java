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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** lodFromExploredChunks (4.6.0) is on by default, for a new install and for a config written before it existed. */
public class GsonConfigExploredLodTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path configPath() throws IOException {
        return folder.newFolder("chunksmith").toPath().resolve("config.json");
    }

    @Test
    public void aNewInstallTakesExploredChunks() throws IOException {
        assertTrue(new GsonConfig(configPath()).isLodFromExploredChunksEnabled());
    }

    @Test
    public void aConfigFromBeforeTheKeyExistedTakesThemToo() throws IOException {
        Path path = configPath();
        Files.writeString(path, "{\"lodEnabled\": \"auto\"}", StandardCharsets.UTF_8);
        assertTrue(new GsonConfig(path).isLodFromExploredChunksEnabled());
    }

    @Test
    public void turningItOffIsSavedAndSticks() throws IOException {
        Path path = configPath();
        new GsonConfig(path).setLodFromExploredChunksEnabled(false);
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains("\"lodFromExploredChunks\": false"));
        assertFalse(new GsonConfig(path).isLodFromExploredChunksEnabled());
    }
}
