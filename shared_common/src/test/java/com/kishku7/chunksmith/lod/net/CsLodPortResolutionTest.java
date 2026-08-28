package com.kishku7.chunksmith.lod.net;

import com.kishku7.chunksmith.command.ConfigSetting;
import com.kishku7.chunksmith.command.ConfigSettings;
import com.kishku7.chunksmith.platform.Config;
import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;

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
    public void deriveFailsAtTheTopOfTheRange() {
        assertEquals(0, CsLodProtocol.httpPort(65535, 0));
    }

    @Test
    public void explicitPortIsKept() {
        // The whole point of the key: the operator names a port their host actually gave them, and
        // that is the port, with no arithmetic applied to it.
        assertEquals(30000, CsLodProtocol.httpPort(25565, 30000));
    }

    @Test
    public void anExplicitPortWinsOverTheDerivedOne() {
        assertEquals("a configured port wins",
                40000, CsLodProtocol.httpPort(25565, 40000));
    }

    @Test
    public void theGamesOwnPortIsRefused() {
        // Binding it cannot succeed -- the game holds it -- and refusing here lets the caller report
        // a cause instead of an anonymous bind failure the operator has to guess at.
        assertEquals(0, CsLodProtocol.httpPort(25565, 25565));
    }

    @Test
    public void privilegedAndOutOfRangePortsAreRefused() {
        assertEquals("a privileged port",
                0, CsLodProtocol.httpPort(25565, 80));
        assertEquals(0, CsLodProtocol.httpPort(25565, 1023));
        assertEquals(0, CsLodProtocol.httpPort(25565, 65536));
        assertEquals(0, CsLodProtocol.httpPort(25565, -1));
    }

    @Test
    public void boundariesAreLegal() {
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
    public void settingHasACommand() {
        // ConfigSettingsCoverageTest proves every config key has a command; this proves THIS key is
        // spelled the way an operator would type it, and is found case-insensitively like the rest.
        assertTrue(ConfigSettings.find("lodBackchannelPort").isPresent());
        assertTrue(ConfigSettings.find("lodbackchannelport").isPresent());
        assertEquals(ConfigSetting.Kind.INTEGER,
                ConfigSettings.find("lodBackchannelPort").orElseThrow().kind());
    }

    @Test
    public void rebindIsANoOpWhenUnregistered() {
        // Bukkit, or before a server exists. It must report "nothing to rebind" rather than claim a
        // port moved -- a setting that silently does nothing is the failure this issue is about.
        CsLodControl.clear();
        assertFalse("apply() must be empty, not 0",
                CsLodControl.apply().isPresent());
        assertFalse("and it must not invent a game port either", CsLodControl.gamePort().isPresent());
        assertTrue("describe() must be absent, not empty",
                CsLodControl.describe().isEmpty());
    }

    @Test
    public void rebindCallsTheRegisteredActionAndReportsItsPort() {
        final int[] calls = {0};
        CsLodControl.register(() -> {
            calls[0]++;
            return 30000;
        }, () -> 25565, () -> "backchannel: port 30000 (configured)");
        try {
            final OptionalInt result = CsLodControl.apply();
            assertTrue(result.isPresent());
            assertEquals(30000, result.getAsInt());
            assertEquals(1, calls[0]);
            assertEquals(25565, CsLodControl.gamePort().orElseThrow());
            assertEquals("backchannel: port 30000 (configured)", CsLodControl.describe().orElseThrow());
        } finally {
            CsLodControl.clear();
        }
    }

    @Test
    public void clearStopsRebinds() {
        CsLodControl.register(() -> 1, () -> 25565, () -> "x");
        CsLodControl.clear();
        assertFalse(CsLodControl.apply().isPresent());
    }

    // --- the game-port refusal (found by driving a live server, not by reading the code) -----------

    @Test
    public void theGamesOwnPortIsNeverStored() {
        // The bind refuses it too, but a bind happens after the write. Accepting it here stored a
        // value that killed the backchannel, answered "done", and kept it dead across every restart.
        CsLodControl.register(() -> 0, () -> 25565, () -> "x");
        try {
            final int[] seen = {0, -1};
            assertFalse("the game's own port",
                    port().write(recording(seen), "25565"));
            assertEquals("refused write reached the config", 0, seen[0]);
        } finally {
            CsLodControl.clear();
        }
    }

    @Test
    public void aLegalPortIsAcceptedWhileRunning() {
        CsLodControl.register(() -> 30000, () -> 25565, () -> "x");
        try {
            final int[] seen = {0, -1};
            assertTrue(port().write(recording(seen), "30000"));
            assertEquals(1, seen[0]);
            assertEquals(30000, seen[1]);
        } finally {
            CsLodControl.clear();
        }
    }

    @Test
    public void zeroIsAcceptedWhileRunning() {
        // 0 means "derive" and must never be caught by the game-port guard.
        CsLodControl.register(() -> 25566, () -> 25565, () -> "x");
        try {
            final int[] seen = {0, -1};
            assertTrue(port().write(recording(seen), "0"));
            assertEquals(0, seen[1]);
        } finally {
            CsLodControl.clear();
        }
    }

    @Test
    public void aWordIsRefused() {
        final int[] seen = {0, -1};
        assertFalse(port().write(recording(seen), "nonsense"));
        assertEquals(0, seen[0]);
    }

    private static ConfigSetting port() {
        return ConfigSettings.find("lodBackchannelPort").orElseThrow();
    }

    /**
     * A Config that records only what this test cares about.
     *
     * <p>A proxy rather than a hand-written stub: Config carries around forty methods and none of
     * the other thirty-nine have anything to do with a port, so implementing them would be noise
     * that has to be maintained every time the interface grows.
     *
     * @param seen {@code [writeCount, lastPortWritten]}
     */
    private static Config recording(int[] seen) {
        return (Config) Proxy.newProxyInstance(
                Config.class.getClassLoader(),
                new Class<?>[]{Config.class},
                (proxy, method, args) -> {
                    if ("setLodBackchannelPort".equals(method.getName())) {
                        seen[0]++;
                        seen[1] = (Integer) args[0];
                        return null;
                    }
                    final Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return Boolean.FALSE;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == double.class) {
                        return 0.0d;
                    }
                    return null;
                });
    }
}
