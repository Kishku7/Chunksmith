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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * The backchannel token is the player's credential, and it is bound to the PLAYER alone (mod_support #50).
 * It used to carry the IP the player joined from as well, which rejected every download made through a
 * reverse proxy or a Docker port mapping, where the request arrives from the proxy and not the player.
 *
 * <p>What stands in for the old IP check is the secret itself: a UUID or a name is public, the token is not.
 */
public class CsLodTokensTest {

    private static final CsLodTokens.OnlineCheck ONLINE = player -> true;

    @Test
    public void aTokenValidatesForItsPlayer() {
        CsLodTokens tokens = new CsLodTokens();
        UUID player = UUID.randomUUID();

        assertEquals(player, tokens.validate(tokens.issue(player), ONLINE));
    }

    // The whole point of #50: nothing about where the request came from is part of the decision.
    @Test
    public void aGuessedOrForgedTokenIsRefused() {
        CsLodTokens tokens = new CsLodTokens();
        UUID player = UUID.randomUUID();
        tokens.issue(player);

        assertNull(tokens.validate("not-the-token", ONLINE));
        assertNull(tokens.validate("", ONLINE));
        assertNull(tokens.validate(null, ONLINE));
    }

    // Knowing somebody's UUID or name must get an attacker nothing: only the token proves anything.
    @Test
    public void twoPlayersGetDifferentTokensAndEachOnlyValidatesAsItself() {
        CsLodTokens tokens = new CsLodTokens();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String forAlice = tokens.issue(alice);
        String forBob = tokens.issue(bob);

        assertNotEquals(forAlice, forBob);
        assertEquals(alice, tokens.validate(forAlice, ONLINE));
        assertEquals(bob, tokens.validate(forBob, ONLINE));
    }

    @Test
    public void aTokenStopsWorkingTheMomentThePlayerLeaves() {
        CsLodTokens tokens = new CsLodTokens();
        UUID player = UUID.randomUUID();
        String token = tokens.issue(player);
        AtomicBoolean online = new AtomicBoolean(true);

        assertEquals(player, tokens.validate(token, p -> online.get()));
        online.set(false);
        assertNull(tokens.validate(token, p -> online.get()));

        online.set(true);
        tokens.revoke(player);
        assertNull(tokens.validate(token, p -> online.get()));
    }

    // Issuing again (a rejoin, or the client refreshing) retires the old token, so only one is ever live.
    @Test
    public void issuingAgainRetiresTheOldToken() {
        CsLodTokens tokens = new CsLodTokens();
        UUID player = UUID.randomUUID();
        String first = tokens.issue(player);
        String second = tokens.issue(player);

        assertNull(tokens.validate(first, ONLINE));
        assertEquals(player, tokens.validate(second, ONLINE));
        assertEquals(1, tokens.size());
    }
}
