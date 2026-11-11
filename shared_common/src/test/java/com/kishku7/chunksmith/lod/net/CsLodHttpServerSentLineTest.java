/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The console line for a finished run of backchannel transfers names the player and the amount. */
public class CsLodHttpServerSentLineTest {

    @Test
    public void namesThePlayerTheFilesAndTheSize() {
        String line = CsLodHttpServer.describeSent("Kishku7", 79, 94_196_594L, 12);
        assertEquals("Chunksmith: sent Kishku7 79 LOD files (89.8 MB) over the backchannel in 12s", line);
    }

    @Test
    public void oneFileIsNotPlural() {
        String line = CsLodHttpServer.describeSent("Alex", 1, 2_048L, 1);
        assertTrue(line, line.contains("1 LOD file ("));
        assertTrue(line, line.contains("2.0 KB"));
    }
}
