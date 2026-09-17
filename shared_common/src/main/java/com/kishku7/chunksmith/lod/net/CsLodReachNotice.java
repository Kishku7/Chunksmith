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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says so when the LOD backchannel is running and nothing has ever arrived on it.
 *
 * <p><b>Why this exists.</b> Three separate operators (mod_support #24, #26, #31) have reported
 * "clients are not getting LOD" where the cause was that the backchannel's TCP port was never
 * reachable from the internet -- not forwarded, or behind a proxy that only forwards the game port.
 * The server already prints the port at startup and says to open it; all three missed it, because
 * one INFO line at boot is invisible in a modded server's startup spam. That is the same shape as
 * #27, where the message was not missing -- its level and its timing were wrong.
 *
 * <p><b>So this fires at the moment of failure instead.</b> The signature is unambiguous and the
 * server can see it without asking anyone: the HTTP server is bound, a client has negotiated LOD
 * (it is asking for regions), and the backchannel has served <b>zero</b> files with <b>zero</b>
 * rejections. Zero served AND zero rejected together cannot mean tokens, permissions or an empty
 * store -- a rejection would have been counted. It can only mean no request ever arrived.
 *
 * <p><b>Deliberately not an error, and deliberately not fatal.</b> Nothing is broken: the in-band
 * fallback is carrying the data, just slowly. The operator is being told why their players see LOD
 * only for ground they have walked.
 *
 * <p>Throttled per player rather than globally, and with its own quiet period rather than sharing
 * {@link CsLodCapNotice}'s -- two different diagnoses must not be able to silence each other.
 */
public final class CsLodReachNotice {

    /**
     * How long one notice silences the next for that player.
     *
     * <p>Ten minutes, matching {@link CsLodCapNotice#QUIET_MILLIS}: an in-band fetch can fire many
     * times a minute for a moving player, and sixty identical lines is how a real diagnosis becomes
     * noise an operator filters out.
     */
    public static final long QUIET_MILLIS = 10L * 60L * 1000L;

    private final Map<UUID, Long> lastWarned = new ConcurrentHashMap<>();

    /**
     * Players who have already been handed something to fetch.
     *
     * <p>The FIRST exchange with a client cannot be evidence of anything: it has not had time to
     * come back yet, and warning then would fire on every healthy server. This is what makes the
     * signal "you have had your chance and nothing arrived" rather than "nothing has arrived yet".
     */
    private final Set<UUID> answeredOnce = ConcurrentHashMap.newKeySet();

    /**
     * True when this player's unreachable backchannel is worth mentioning again, and starts the
     * quiet period if so.
     *
     * <p>Not synchronised across players, for the same reason as the cap notice: two threads racing
     * here can at worst print the line twice, and taking a lock on the path that serves LOD to say
     * something cosmetic would be the worse trade.
     */
    public boolean shouldWarn(UUID player, long nowMillis) {
        Long previous = lastWarned.get(player);
        if (previous != null && nowMillis - previous < QUIET_MILLIS) {
            return false;
        }
        lastWarned.put(player, nowMillis);
        return true;
    }

    /**
     * Records that this player has now been given something to fetch, and says whether they had
     * already been given something before.
     *
     * @return false the first time for a player, true on every later call
     */
    public boolean hasBeenAnsweredBefore(UUID player) {
        return !answeredOnce.add(player);
    }

    /** Forgets a player, so their next fetch is reported afresh. Called when they disconnect. */
    public void forget(UUID player) {
        lastWarned.remove(player);
        answeredOnce.remove(player);
    }

    /** Drops every player. Called when the LOD server stops. */
    public void clear() {
        lastWarned.clear();
        answeredOnce.clear();
    }

    /**
     * Is the backchannel up but demonstrably unreached?
     *
     * @param running    whether the HTTP server is bound at all -- false means the operator turned
     *                   it off or it failed to bind, which is a different situation and has its own
     *                   message; this notice would be wrong there
     * @param served     files the backchannel has delivered since it started
     * @param rejected   requests it refused (bad or expired token)
     * @return true only when it is running and BOTH counters are zero
     */
    public static boolean unreached(boolean running, long served, long rejected) {
        return running && served == 0L && rejected == 0L;
    }

    /**
     * The operator-facing diagnosis, given the port nothing is arriving on.
     *
     * <p><b>The consequence differs by platform and the message must not lie about it.</b> The mod
     * has an in-band fallback, so an unreachable backchannel is SLOW. The plugin has none, so the
     * same fault means those clients get NO LOD at all. Telling a Paper operator their players are
     * "falling back to a slower channel" would have them wait for something that is never coming.
     *
     * @param port              the backchannel port nothing has arrived on
     * @param inBandFallback    true on the mod, false on the Bukkit plugin
     */
    public static String explain(int port, boolean inBandFallback) {
        String consequence = inBandFallback
                ? " so clients are falling back to the slow in-band channel and will only get LOD"
                  + " for ground they have walked."
                : " and this platform has NO in-band fallback, so those clients are getting no LOD"
                  + " at all.";
        String closing = inBandFallback
                ? " Nothing is broken and no data is lost; this is only about speed."
                : " Nothing is damaged and no data is lost -- but until that port is reachable, LOD"
                  + " will not arrive.";
        return "Chunksmith: the LOD backchannel on port " + port + " is running but has never"
                + " received a single request," + consequence
                + " That port is almost certainly not reachable from your players: open or forward"
                + " TCP " + port + " exactly as you did the game port, or -- if the game reaches"
                + " them by a different address, such as through a proxy -- point them somewhere"
                + " routable with '/cs set lodBackchannelHost <host>' and"
                + " '/cs set lodBackchannelPort <port>'." + closing;
    }
}
