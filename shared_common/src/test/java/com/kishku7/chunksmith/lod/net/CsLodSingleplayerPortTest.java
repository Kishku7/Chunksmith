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

package com.kishku7.chunksmith.lod.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A singleplayer world has no game port. mod_support #37: the backchannel derived "-1 + 1 = 0",
 * warned that there was no room for a port, and then told the player port 0 had never been
 * reached. There is nothing to bind and nobody to serve; it must simply not bind.
 */
public class CsLodSingleplayerPortTest {

    @Test
    public void anUnpublishedWorldBindsNothing() {
        CsLodHttpServer server = new CsLodHttpServer(dimension -> null, null, player -> false);
        assertEquals(0, server.start(null, -1, 0, ""));
        assertEquals(0, server.getPort());
    }
}
