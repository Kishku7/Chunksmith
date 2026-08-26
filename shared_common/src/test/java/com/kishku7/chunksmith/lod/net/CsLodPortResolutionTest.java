package com.kishku7.chunksmith.lod.net;

import com.kishku7.chunksmith.command.ConfigSetting;
import com.kishku7.chunksmith.command.ConfigSettings;
import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The backchannel port an operator actually gets (mod_support #19).
 *
 * <p>Before 3.14.0 the port was {@code gamePort + 1} and nothing else, which is unusable on a managed
 * host that will not rent you the port next to your game port. The rules that replaced it are small but
 * every one of them decides whether somebody's server can serve LODs at all, so they are pinned here
 * rather than left to be re-derived from the code each time somebody touches it.
 */
public class CsLodPortResolutionTest {

    @Test
    public void zeroMeansDeriveFromTheGamePort() {
        assertEquals("0 must keep the historical behaviour -- it is the default, and an existing"
                + " server must not change what it binds after an update",
                25566, CsLodProtocol.httpPort(25565, 0));
    }

    @Test
    public void deriveHasNowhereToGoFromTheTopOfTheRange() {
        assertEquals(0, CsLodProtocol.httpPort(65535, 0));
    }

    @Test
    public void anExplicitPortIsHonouredExactly() {
        // The whole point of the key: the operator names a port their host actually gave them, and
        // that is the port, with no arithmetic applied to it.
        assertEquals(30000, CsLodProtocol.httpPort(25565, 30000));
    }

    @Test
    public void anExplicitPortWinsEvenWhenTheDerivedOneWouldHaveWorked() {
        assertEquals("a configured port is an operator decision, never second-guessed",
                40000, CsLodProtocol.httpPort(25565, 40000));
    }

    @Test
    public void theGamesOwnPortIsRefusedRatherThanAttempted() {
        // Binding it cannot succeed -- the game holds it -- and refusing here lets the caller report
        // a cause instead of an anonymous bind failure the operator has to guess at.
        assertEquals(0, CsLodProtocol.httpPort(25565, 25565));
    }

    @Test
    public void privilegedAndOutOfRangePortsAreRefused() {
        assertEquals("a privileged port is far likelier to be a typo than an intention",
                0, CsLodProtocol.httpPort(25565, 80));
        assertEquals(0, CsLodProtocol.httpPort(25565, 1023));
        assertEquals(0, CsLodProtocol.httpPort(25565, 65536));
        assertEquals(0, CsLodProtocol.httpPort(25565, -1));
    }

    @Test
    public void theBoundariesThemselvesAreLegal() {
        assertEquals(1024, CsLodProtocol.httpPort(25565, 1024));
        assertEquals(65535, CsLodProtocol.httpPort(25565, 65535));
    }

    @Test
    public void theSingleArgumentFormStillDerives() {
        assertEquals("the old signature is still used by nothing-configured paths and must not drift"
                + " from the two-argument form", CsLodProtocol.httpPort(25565, 0),
                CsLodProtocol.httpPort(25565));
    }

    // --- the command seam -------------------------------------------------------------------------

    @Test
    public void theSettingIsReachableFromTheCommand() {
        // ConfigSettingsCoverageTest proves every config key has a command; this proves THIS key is
        // spelled the way an operator would type it, and is found case-insensitively like the rest.
        assertTrue(ConfigSettings.find("lodBackchannelPort").isPresent());
        assertTrue(ConfigSettings.find("lodbackchannelport").isPresent());
        assertEquals(ConfigSetting.Kind.INTEGER,
                ConfigSettings.find("lodBackchannelPort").orElseThrow().kind());
    }

    @Test
    public void rebindIsANoOpWhenNothingHasRegistered() {
        // Bukkit, or before a server exists. It must report "nothing to rebind" rather than claim a
        // port moved -- a setting that silently does nothing is the failure this issue is about.
        CsLodRebind.clear();
        final OptionalInt result = CsLodRebind.apply();
        assertFalse("with no action registered, apply() must be empty, not 0", result.isPresent());
    }

    @Test
    public void rebindCallsTheRegisteredActionAndReportsItsPort() {
        final int[] calls = {0};
        CsLodRebind.register(() -> {
            calls[0]++;
            return 30000;
        });
        try {
            final OptionalInt result = CsLodRebind.apply();
            assertTrue(result.isPresent());
            assertEquals(30000, result.getAsInt());
            assertEquals(1, calls[0]);
        } finally {
            CsLodRebind.clear();
        }
    }

    @Test
    public void clearStopsAStoppedServerFromEverBeingRebound() {
        CsLodRebind.register(() -> 1);
        CsLodRebind.clear();
        assertFalse(CsLodRebind.apply().isPresent());
    }
}
