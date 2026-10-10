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

/**
 * The world-enter pregen defaults to auto from 4.5.0 (mod_support #45): spawn for a world that never
 * finished a world-enter run, that run's own centre for one that did. A saved explicit value must
 * still win, and an install with no value must get auto rather than origin or spawn.
 */
public class GsonConfigWorldEnterCenterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path configPath() throws IOException {
        return folder.newFolder("chunksmith").toPath().resolve("config.json");
    }

    @Test
    public void aNewInstallIsAutomatic() throws IOException {
        assertEquals("auto", new GsonConfig(configPath()).getWorldEnterPregenCenter());
    }

    @Test
    public void anInstallThatSavedOriginKeepsIt() throws IOException {
        Path path = configPath();
        Files.writeString(path, "{\"worldEnterPregenCenter\": \"origin\"}", StandardCharsets.UTF_8);
        assertEquals("origin", new GsonConfig(path).getWorldEnterPregenCenter());
    }

    @Test
    public void theSavedFileCarriesTheValueSoALaterDefaultCannotMoveIt() throws IOException {
        Path path = configPath();
        GsonConfig config = new GsonConfig(path);
        config.setWorldEnterPregenCenter("origin");
        String saved = Files.readString(path, StandardCharsets.UTF_8);
        assertEquals(true, saved.contains("\"worldEnterPregenCenter\": \"origin\""));
    }

    /**
     * The case auto exists for: a config written before 4.3.0 has no such key and is never rewritten
     * unless somebody changes a setting. It must read auto -- never plain spawn, which would send a
     * world it already finished around origin back behind the freeze screen.
     */
    @Test
    public void aConfigFromBeforeTheKeyExistedIsAutomatic() throws IOException {
        Path path = configPath();
        Files.writeString(path, "{\"worldEnterPregenRadius\": 4096}", StandardCharsets.UTF_8);
        assertEquals("auto", new GsonConfig(path).getWorldEnterPregenCenter());
    }

    @Test
    public void nonsenseFallsBackToTheDefault() throws IOException {
        Path path = configPath();
        Files.writeString(path, "{\"worldEnterPregenCenter\": \"over there\"}", StandardCharsets.UTF_8);
        assertEquals("auto", new GsonConfig(path).getWorldEnterPregenCenter());
    }
}
