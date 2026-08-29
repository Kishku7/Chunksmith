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

package com.kishku7.chunksmith.lod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says out loud, ONCE, when a renderer we detected turns out not to work the way we expected.
 * <p>That happened for real: a fork that declares {@code int sectionRenderDistance} where upstream
 * voxy declares {@code float} produced a {@code NoSuchFieldError}, which was swallowed, which
 * silently collapsed the LOD radius from 8192 blocks to the 256-block protocol default -- a 32x
 * collapse, reported as success. Never again: a renderer that fails to accept our data, or whose
 * settings we cannot read, is a thing the player must be told about, in words, naming what broke and
 * what we did instead.
 */
public final class LodWarnings {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private LodWarnings() {
    }

    public static void once(String cause, String message) {
        if (SAID.add(cause)) {
            LOGGER.warn("Chunksmith: {}", message);
        }
    }

    public static boolean saidAlready(String cause) {
        return SAID.contains(cause);
    }

    public static void reset() {
        SAID.clear();
    }
}
