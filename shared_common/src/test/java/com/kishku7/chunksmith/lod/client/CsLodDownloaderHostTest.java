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

package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.util.Input;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * mod_support #26 -- a hostname containing an underscore killed the LOD backchannel.
 *
 * <p>The report blamed {@code URI.create}. It is not the culprit, and the first test here pins the
 * real mechanism so nobody "fixes" it by wrapping the wrong call: the URI parses perfectly well,
 * but an underscore is illegal in an RFC-2396 server-based authority, so Java falls back to a
 * REGISTRY-based one and quietly reports {@code getHost() == null} / {@code getPort() == -1}. The
 * throw arrives later, from {@code HttpRequest.newBuilder}.
 */
public class CsLodDownloaderHostTest {

    private static final int PORT = 25568;

    // --- the mechanism itself, pinned so a future reader does not have to rediscover it ----------

    @Test
    public void anUnderscoreHostParsesAsAUriButLosesItsHostAndPort() {
        URI uri = URI.create("http://myserver_minecraft.mydomain.com:25568/test");
        assertNull("URI.create does NOT throw and does NOT reject it -- it silently yields no host",
                uri.getHost());
        assertEquals("and the port is lost with it, which is the second landmine", -1, uri.getPort());
        assertEquals("the authority is still all there, as a registry-based one",
                "myserver_minecraft.mydomain.com:25568", uri.getAuthority());
    }

    // --- detection ------------------------------------------------------------------------------

    @Test
    public void theReportedHostIsDetected() {
        assertTrue(CsLodDownloader.needsAddressResolution("myserver_minecraft.mydomain.com", PORT));
    }

    @Test
    public void ordinaryHostsNeedNothing() {
        assertFalse(CsLodDownloader.needsAddressResolution("myserver.mydomain.com", PORT));
        assertFalse(CsLodDownloader.needsAddressResolution("my-server.mydomain.com", PORT));
        assertFalse(CsLodDownloader.needsAddressResolution("localhost", PORT));
        assertFalse(CsLodDownloader.needsAddressResolution("1host.example.com", PORT));
    }

    @Test
    public void addressLiteralsNeedNothing() {
        assertFalse(CsLodDownloader.needsAddressResolution("192.168.1.10", PORT));
        assertFalse(CsLodDownloader.needsAddressResolution("[::1]", PORT));
    }

    // --- rewriting ------------------------------------------------------------------------------

    @Test
    public void aUsableHostIsPassedThroughUntouched() {
        // No DNS lookup happens on this path at all, which is why it is safe to call per download.
        assertEquals("myserver.mydomain.com",
                CsLodDownloader.resolvableHost("myserver.mydomain.com", PORT));
        assertEquals("192.168.1.10", CsLodDownloader.resolvableHost("192.168.1.10", PORT));
        assertEquals("[::1]", CsLodDownloader.resolvableHost("[::1]", PORT));
    }

    @Test
    public void localhostStillResolvesToSomethingUsable() {
        String resolved = CsLodDownloader.resolvableHost("localhost", PORT);
        assertEquals("localhost", resolved);
        // Whatever comes back must itself be a legal URL host, or we have moved the bug rather than
        // fixed it.
        assertEquals("localhost", URI.create("http://" + resolved + ":" + PORT + "/").getHost());
    }

    @Test
    public void anUnresolvableUnderscoreHostReportsFailureRatherThanThrowing() {
        // .invalid is reserved by RFC 2606 and must never resolve, so this is deterministic offline.
        assertNull("a host we cannot use AND cannot resolve must come back as null, so the caller"
                        + " can log it once and fall back to the in-band channel",
                CsLodDownloader.resolvableHost("my_server.example.invalid", PORT));
    }

    // --- the config half of the same bug --------------------------------------------------------

    @Test
    public void theHostKeyAcceptsUnderscoresSoTheServerCanBeConfiguredAtAll() {
        // Before #26 this returned "" -- the key silently reset to unset, and an operator whose
        // server really is named this way could not configure the backchannel and was told nothing.
        assertEquals("myserver_minecraft.mydomain.com",
                Input.checkHost("myserver_minecraft.mydomain.com"));
        assertEquals("my_server", Input.checkHost("my_server"));
    }

    @Test
    public void wideningToUnderscoresDidNotLetTheRestOfTheRulesSlip() {
        assertEquals("", Input.checkHost("-leadinghyphen.example.com"));
        assertEquals("", Input.checkHost("trailinghyphen-.example.com"));
        assertEquals("", Input.checkHost("double..dot"));
        assertEquals("", Input.checkHost("has space.example.com"));
        assertEquals("", Input.checkHost("bad/slash.example.com"));
        assertEquals("", Input.checkHost(""));
    }
}
