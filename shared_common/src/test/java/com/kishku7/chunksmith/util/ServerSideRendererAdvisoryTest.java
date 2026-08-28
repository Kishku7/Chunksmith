package com.kishku7.chunksmith.util;

import org.junit.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.List;

/**
 * The advisory has exactly one job and one way to get it badly wrong: firing on a client.
 *
 * <p>A single-player world runs an integrated server inside a client that absolutely does need a
 * renderer, so telling that player to remove Distant Horizons would be advice that breaks their game.
 * Every test here exists to keep that impossible.
 */
public class ServerSideRendererAdvisoryTest {

    private static final Set<String> BOTH = Set.of("distanthorizons", "voxy");

    @Test
    public void silentOnAnIntegratedServerWithEveryRenderer() {
        assertFalse("an integrated server needs the renderer",
                ServerSideRendererAdvisory.message(false, BOTH::contains).isPresent());
    }

    @Test
    public void silentWithNoRenderer() {
        assertFalse(ServerSideRendererAdvisory.message(true, id -> false).isPresent());
    }

    @Test
    public void namesTheRendererItFound() {
        Optional<String> message =
                ServerSideRendererAdvisory.message(true, "distanthorizons"::equals);
        assertTrue(message.isPresent());
        assertTrue(message.get().startsWith("distanthorizons is installed on this DEDICATED SERVER"));
        assertFalse("only report what is actually installed", message.get().contains("voxy"));
    }

    @Test
    public void namesBothRenderers() {
        String message = ServerSideRendererAdvisory.message(true, BOTH::contains).orElseThrow();
        assertTrue(message.contains("distanthorizons and voxy are installed"));
        assertTrue(message.contains("does not need them"));
    }

    @Test
    public void saysWhyNotJustWhat() {
        String message = ServerSideRendererAdvisory.message(true, BOTH::contains).orElseThrow();
        // A warning an operator cannot act on is noise. It has to say what to do and when NOT to.
        assertTrue("must say what to do", message.contains("Removing it is the recommended setup"));
        assertTrue("must say when keeping it is right",
                message.contains("players who do not have Chunksmith installed"));
    }

    @Test
    public void onlyAdvisesAboutOurRenderers() {
        assertEquals(List.of("distanthorizons", "voxy"),
                ServerSideRendererAdvisory.rendererIds());
        assertFalse("an unrelated mod is ignored",
                ServerSideRendererAdvisory.message(true, "some_other_mod"::equals).isPresent());
    }
}
