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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether the "index was capped" warning is worth printing again for a
 * player, and writes the sentence that goes with it.
 *
 * <p>It exists because of how often the question gets asked. An index is not
 * rebuilt on a timer: the client asks again every time the player has travelled
 * half a region, with a five-second floor under it, so a player on an elytra
 * asks roughly every nine seconds. Warning per answer produced six lines in
 * seventy-three seconds in the report that started this (mod_support #23), and
 * the reading it invited was that something was looping. Nothing was looping.
 * The travel refresh was working exactly as designed; only the logging was
 * wrong.
 *
 * <p>The other half of the fix is what the line says. It used to promise that
 * the client would "get the rest as it travels", which is true when the region
 * cap trims a nearest-first list -- travel moves the list -- and false when an
 * operator has set a byte budget, because then the same budget trims the same
 * way wherever the player stands. Two causes, two sentences, and neither of
 * them guesses.
 *
 * <p>Shared rather than written twice: the loaders and the Bukkit plugin both
 * answer index requests, and a throttle implemented once on each side is a
 * throttle that will eventually disagree with itself. Keyed by UUID rather than
 * by name, because that is what both platforms' disconnect hooks are keyed by
 * and an entry that cannot be removed on the same key it was added under is a
 * map that grows for the life of the server.
 */
public final class CsLodCapNotice {

    /**
     * How long a warning silences the next one for that player. Ten minutes is
     * long enough that a travelling player gets one line rather than sixty, and
     * short enough that an operator who goes looking still finds a recent one.
     */
    public static final long QUIET_MILLIS = 10L * 60L * 1000L;

    private final Map<UUID, Long> lastWarned = new ConcurrentHashMap<>();

    /**
     * Returns true when this player's cap is worth mentioning again, and starts
     * the quiet period if so.
     *
     * <p>Answers for one player at a time and does not synchronise across them:
     * two threads racing here can at worst print the line twice, which is a
     * cosmetic loss, and locking a logging decision on the path that answers
     * index requests would not be.
     */
    public boolean shouldWarn(UUID player, long nowMillis) {
        Long previous = lastWarned.get(player);
        if (previous != null && nowMillis - previous < QUIET_MILLIS) {
            return false;
        }
        lastWarned.put(player, nowMillis);
        return true;
    }

    /** Forgets a player, so their next capped answer is reported. Called when they disconnect. */
    public void forget(UUID player) {
        lastWarned.remove(player);
    }

    /** Drops every player. Called when the LOD server stops. */
    public void clear() {
        lastWarned.clear();
    }

    /**
     * The half of the warning that explains the cause, given which cap bound.
     *
     * @param result the scan whose caps are being explained; must be {@link CsLodIndexScan.Result#capped()}
     * @param budgetMb the operator's configured budget in megabytes, or 0 if they have not set one
     */
    public static String explain(CsLodIndexScan.Result result, long budgetMb) {
        if (result.cappedByBudget()) {
            return "lodIndexBudgetMb is set to " + budgetMb + " MB, which is what stopped the list."
                    + " Regions past it will not be sent from anywhere the player stands; raise it or"
                    + " set it to 0 for no limit.";
        }
        // The region cap is a nearest-first trim, so the set genuinely moves with the player and the
        // old "you will get the rest as you travel" line is true here -- and only here.
        return "the hard limit of " + CsLodIndexScan.MAX_REGIONS + " regions per answer stopped the"
                + " list. This one is measured nearest-first from the player, so travelling does"
                + " bring the rest in.";
    }
}
