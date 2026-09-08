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

package com.kishku7.chunksmith.util;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * This has exactly one job and one way to get it badly wrong: firing on a client.
 *
 * <p>A single-player world runs an integrated server inside a client that absolutely does need a
 * renderer, so telling that player to remove Distant Horizons would be advice that breaks their
 * game -- and it now arrives in red, which makes being wrong worse than it used to be. Every test
 * here exists to keep that impossible.
 */
public class ServerSideRendererErrorTest {

    private static final Set<String> BOTH = Set.of("distanthorizons", "voxy");

    private static String joined(List<String> lines) {
        return String.join(" ", lines);
    }

    @Test
    public void silentOnAnIntegratedServerWithEveryRenderer() {
        assertTrue("an integrated server needs the renderer",
                ServerSideRendererError.lines(false, BOTH::contains).isEmpty());
    }

    @Test
    public void silentWithNoRenderer() {
        assertTrue(ServerSideRendererError.lines(true, id -> false).isEmpty());
    }

    @Test
    public void namesTheRendererItFound() {
        String message = joined(ServerSideRendererError.lines(true, "distanthorizons"::equals));
        assertTrue(message.contains("distanthorizons is installed on this DEDICATED SERVER"));
        assertFalse("only report what is actually installed", message.contains("voxy"));
    }

    @Test
    public void namesBothRenderers() {
        String message = joined(ServerSideRendererError.lines(true, BOTH::contains));
        assertTrue(message.contains("distanthorizons and voxy are installed"));
        assertTrue("plural reads correctly", message.contains("REMOVE THEM"));
    }

    @Test
    public void tellsTheOperatorWhatToDo() {
        String message = joined(ServerSideRendererError.lines(true, "distanthorizons"::equals));
        // An error an operator cannot act on is noise. It has to say what to do and when NOT to.
        assertTrue("must say what to do", message.contains("REMOVE IT"));
        assertTrue("must say what to do in full", message.contains("Removing it is the recommended setup"));
        assertTrue("must say when keeping it is right",
                message.contains("players who do not have Chunksmith installed"));
    }

    @Test
    public void saysTheModItselfIsFine() {
        // It is logged at ERROR so it cannot be missed, which makes it look like Chunksmith broke.
        // The banner has to say plainly that it did not, or the next report is "your mod errors".
        String message = joined(ServerSideRendererError.lines(true, BOTH::contains));
        assertTrue(message.contains("Chunksmith itself is running normally"));
        assertTrue(message.contains("server misconfiguration"));
    }

    @Test
    public void isABannerOfSeparateLines() {
        // One logger call per line, so every line is red and stamped; an embedded newline would
        // leave all but the first bare. Nothing in here may carry its own newline.
        List<String> lines = ServerSideRendererError.lines(true, BOTH::contains);
        assertTrue("a banner, not one long line", lines.size() > 3);
        for (String line : lines) {
            assertFalse("a line must not embed a newline: " + line, line.contains("\n"));
        }
    }

    @Test
    public void onlyReportsOurRenderers() {
        assertEquals(List.of("distanthorizons", "voxy"), ServerSideRendererError.rendererIds());
        assertTrue("an unrelated mod is ignored",
                ServerSideRendererError.lines(true, "some_other_mod"::equals).isEmpty());
    }
}
