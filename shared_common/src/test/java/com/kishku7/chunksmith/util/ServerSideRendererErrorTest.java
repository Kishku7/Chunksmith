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
    public void callsTheRendererWhatItsAuthorCalls() {
        // Detection is by mod id, but nobody installed a mod called "distanthorizons". An operator
        // reading this has to recognise the thing on his own disk.
        String message = joined(ServerSideRendererError.lines(true, "distanthorizons"::equals));
        assertTrue(message.contains("Distant Horizons is installed on this DEDICATED SERVER"));
        assertFalse("never show the raw mod id", message.contains("distanthorizons"));
        assertFalse("only report what is actually installed", message.toLowerCase().contains("voxy"));
    }

    @Test
    public void namesBothRenderers() {
        String message = joined(ServerSideRendererError.lines(true, BOTH::contains));
        assertTrue(message.contains("Distant Horizons and Voxy are installed"));
        assertTrue("plural reads correctly", message.contains("REMOVE THEM"));
        assertFalse("never show the raw mod id", message.contains("distanthorizons"));
    }

    @Test
    public void reportsVoxyOnItsOwn() {
        // Voxy is not a footnote to the DH case: it earns the same red banner by itself.
        String message = joined(ServerSideRendererError.lines(true, "voxy"::equals));
        assertTrue(message.contains("Voxy is installed on this DEDICATED SERVER"));
        assertTrue(message.contains("REMOVE IT"));
        assertFalse("Distant Horizons is not installed, so do not mention it",
                message.contains("Distant Horizons"));
    }

    @Test
    public void keepsTheDhOnlyReasoningOutOfAVoxyReport() {
        // The dhpush console spam and the "you may legitimately serve vanilla DH clients" carve-out
        // are both facts about Distant Horizons. Printed at a Voxy operator they send him looking
        // for a log line that is not there and a command that has nothing to do with him.
        String voxyOnly = joined(ServerSideRendererError.lines(true, "voxy"::equals));
        assertFalse(voxyOnly.contains("dhpush"));
        assertFalse(voxyOnly.contains("players who do not have Chunksmith installed"));
        assertTrue("say instead why Voxy has no case at all",
                voxyOnly.contains("Voxy has no server half"));

        String withDh = joined(ServerSideRendererError.lines(true, BOTH::contains));
        assertTrue("with DH present the DH reasoning belongs there", withDh.contains("dhpush"));
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
    public void reportsTheRenamedForkUnderItsOwnName() {
        // neo-voxy is the one fork that changed its mod id. It reaches the no-server-half branch,
        // and it must not be told it is Voxy.
        String message = joined(ServerSideRendererError.lines(true, "neovoxy"::equals));
        assertTrue(message.contains("neo-voxy is installed on this DEDICATED SERVER"));
        assertTrue(message.contains("neo-voxy has no server half"));
        assertFalse(message.contains("Voxy has no server half"));
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
        // The IDS stay raw -- they are what a loader is asked about. Only the DISPLAY is prettied.
        // And the list is RendererNames', so this cannot drift from what the LOD detector looks for;
        // the two were already out of step by one fork when they were separate.
        assertEquals(RendererNames.ids(), ServerSideRendererError.rendererIds());
        assertTrue("the renamed fork counts too", ServerSideRendererError.rendererIds().contains("neovoxy"));
        assertTrue("an unrelated mod is ignored",
                ServerSideRendererError.lines(true, "some_other_mod"::equals).isEmpty());
    }
}
