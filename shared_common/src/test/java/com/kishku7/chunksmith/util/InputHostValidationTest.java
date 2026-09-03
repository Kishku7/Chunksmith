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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What may go in the two backchannel address keys.
 *
 * <p>The rule that drives most of this: **a name whose last label is all digits is not a hostname**
 * (RFC 1123 2.1 reserves that shape so a name can never be confused with an address). So anything
 * ending in digits has to BE a valid address, or it is nothing. Without that, `256.1.1.1` sails
 * through every ordinary hostname rule -- letters-digits-hyphen, no empty label, none too long --
 * and gets stored as an address that can never resolve.
 */
public class InputHostValidationTest {

    // --- valid addresses ------------------------------------------------------------------------

    @Test
    public void ordinaryIpv4IsAccepted() {
        assertEquals("192.168.1.10", Input.checkHost("192.168.1.10"));
        assertEquals("127.0.0.1", Input.checkHost("127.0.0.1"));
        assertEquals("203.0.113.7", Input.checkHost("  203.0.113.7  "));
        assertEquals("255.255.255.255", Input.checkHost("255.255.255.255"));
        assertEquals("0.0.0.0", Input.checkHost("0.0.0.0"));
    }

    @Test
    public void ipv6IsAcceptedBracketedOrNot() {
        assertEquals("::1", Input.checkHost("[::1]"));
        assertEquals("::", Input.checkHost("[::]"));
        assertEquals("2001:db8::1", Input.checkHost("2001:db8::1"));
        assertEquals("", Input.checkHost("2001:db8::zz"));
    }

    // --- INVALID dotted-quads: the point of this whole test class ---------------------------------

    @Test
    public void anOctetAbove255IsRejected() {
        assertEquals("", Input.checkHost("256.1.1.1"));
        assertEquals("", Input.checkHost("1.1.1.999"));
        assertEquals("", Input.checkHost("300.300.300.300"));
    }

    @Test
    public void aDottedNumberThatIsNotFourPartsIsRejected() {
        // Not an address, and not a hostname either -- the last label is all digits.
        assertEquals("", Input.checkHost("1.2.3"));
        assertEquals("", Input.checkHost("1.2.3.4.5"));
        assertEquals("", Input.checkHost("42"));
    }

    @Test
    public void aLeadingZeroOctetIsRejectedBecauseItIsAmbiguous() {
        // "010" is octal to some resolvers and decimal to others, so the same string denotes two
        // different hosts depending on who parses it.
        assertEquals("", Input.checkHost("010.1.1.1"));
        assertEquals("", Input.checkHost("1.1.1.01"));
        // ...but a bare zero octet is fine.
        assertEquals("10.0.0.1", Input.checkHost("10.0.0.1"));
    }

    @Test
    public void anAllNumericTopLabelIsNotAHostname() {
        assertEquals("", Input.checkHost("example.123"));
        assertEquals("", Input.checkHost("myserver.42"));
    }

    // --- DNS-legal names ------------------------------------------------------------------------

    @Test
    public void underscoresAreAccepted() {
        assertEquals("myserver_minecraft.mydomain.com",
                Input.checkHost("myserver_minecraft.mydomain.com"));
        assertEquals("_minecraft._tcp.example.com", Input.checkHost("_minecraft._tcp.example.com"));
        assertEquals("my_server", Input.checkHost("my_server"));
    }

    @Test
    public void aTrailingRootDotIsLegalAndIsKept() {
        // A fully-qualified name. InetAddress accepts it, an operator may well paste one, and it
        // would otherwise split into an empty final label and be refused.
        assertEquals("example.com.", Input.checkHost("example.com."));
        assertEquals("my_server.example.com.", Input.checkHost("my_server.example.com."));
        // A lone dot is not a name.
        assertEquals("", Input.checkHost("."));
    }

    @Test
    public void otherLegalShapesStillPass() {
        assertEquals("1host.example.com", Input.checkHost("1host.example.com"));
        assertEquals("my-server.example.com", Input.checkHost("my-server.example.com"));
        assertEquals("Example.COM", Input.checkHost("Example.COM"));
        assertEquals("localhost", Input.checkHost("localhost"));
        assertEquals("a".repeat(63) + ".example.com", Input.checkHost("a".repeat(63) + ".example.com"));
    }

    @Test
    public void theOldRulesStillHold() {
        assertEquals("", Input.checkHost(null));
        assertEquals("", Input.checkHost(""));
        assertEquals("", Input.checkHost("   "));
        assertEquals("", Input.checkHost("has space"));
        assertEquals("", Input.checkHost("-leading.hyphen"));
        assertEquals("", Input.checkHost("trailing.hyphen-"));
        assertEquals("", Input.checkHost("double..dot"));
        assertEquals("", Input.checkHost("http://lod.example.net"));
        assertEquals("", Input.checkHost("lod.example.net:25566"));
        assertEquals("", Input.checkHost("a".repeat(254)));
        assertEquals("", Input.checkHost("a".repeat(64) + ".example.com"));
    }

    // --- bind address vs advertised host ----------------------------------------------------------

    @Test
    public void aWildcardIsFineToBINDTo() {
        // "every interface on this machine" -- exactly what an operator behind a proxy needs.
        assertEquals("0.0.0.0", Input.checkHost("0.0.0.0"));
        assertEquals("::", Input.checkHost("[::]"));
    }

    @Test
    public void aWildcardIsNeverAValidThingToADVERTISE() {
        // A client told to connect to 0.0.0.0 has been told nothing. Leaving the key EMPTY is how
        // you say "each client should use the address it already connected on".
        assertEquals("", Input.checkAdvertisedHost("0.0.0.0"));
        assertEquals("", Input.checkAdvertisedHost("::"));
        assertEquals("", Input.checkAdvertisedHost("[::]"));
        assertEquals("", Input.checkAdvertisedHost("0:0:0:0:0:0:0:0"));
    }

    @Test
    public void aRealAddressIsStillFineToAdvertise() {
        assertEquals("lod.example.net", Input.checkAdvertisedHost("lod.example.net"));
        assertEquals("203.0.113.7", Input.checkAdvertisedHost("203.0.113.7"));
        assertEquals("myserver_minecraft.mydomain.com",
                Input.checkAdvertisedHost("myserver_minecraft.mydomain.com"));
        assertEquals("::1", Input.checkAdvertisedHost("[::1]"));
    }

    @Test
    public void theAdvertisedCheckStillEnforcesEverythingTheBaseCheckDoes() {
        assertEquals("", Input.checkAdvertisedHost("256.1.1.1"));
        assertEquals("", Input.checkAdvertisedHost("example.123"));
        assertEquals("", Input.checkAdvertisedHost("has space"));
    }

    @Test
    public void wildcardDetectionIsNotOverEager() {
        assertTrue(Input.isWildcardAddress("0.0.0.0"));
        assertTrue(Input.isWildcardAddress("::"));
        assertTrue(Input.isWildcardAddress("[::]"));
        assertTrue(Input.isWildcardAddress("0:0:0:0:0:0:0:0"));
        // These merely CONTAIN zeros; they are real addresses.
        assertFalse(Input.isWildcardAddress("10.0.0.1"));
        assertFalse(Input.isWildcardAddress("::1"));
        assertFalse(Input.isWildcardAddress("2001:db8::1"));
        assertFalse(Input.isWildcardAddress("0.0.0.1"));
        assertFalse(Input.isWildcardAddress("example.com"));
        assertFalse(Input.isWildcardAddress(null));
    }
}
