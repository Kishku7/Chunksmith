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

/**
 * Tells connected players that the store grew, while a pregen is filling it.
 *
 * <p>The client finds out about new LOD by asking: a two-number summary every sync interval (300 s
 * by default), and an index whenever it travels half a region. A player who stands still through a
 * pregen therefore saw the first few regions and then nothing for five minutes at a time
 * (mod_support #45). The server is the one that knows the store just changed, so it now sends that
 * same summary unasked. Every 4.x client already handles an unprompted summary exactly like an
 * answered one -- compare, and pull the index only on a mismatch -- so nothing on the client side
 * had to change for this to work, and the protocol did not move.
 *
 * <p>Two rules decide when. A region is only ever served once it has not been written for {@link
 * CsLodStoreScan#SETTLE_MILLIS}, so a push is worth sending only if something was written that had
 * not yet settled at the previous push; that is also what makes the pushes stop by themselves about
 * one interval after a pregen ends. And at most one push per {@link #PUSH_INTERVAL_MILLIS}: a region
 * that grows is re-fetched whole, and a pregen spiral passes over the same outer regions ring after
 * ring, so pushing on every settle would re-send the same few megabytes over and over.
 *
 * <p>Writes are noted from the store writer thread; the decision is taken on the server thread.
 */
public final class CsLodGrowth {

    /** At most one growth push per this long. Five times the old worst case, a fifth of the sync. */
    public static final long PUSH_INTERVAL_MILLIS = 60_000L;

    private static volatile long lastWriteMillis;

    private static long lastPushMillis;

    private CsLodGrowth() {
    }

    /** Records that the store was just written. Called by the store writer for every record. */
    public static void noteWrite(long nowMillis) {
        lastWriteMillis = nowMillis;
    }

    /**
     * Decides whether to push now, and if so stamps the push. Server thread only.
     *
     * @param nowMillis the current time
     * @return true when connected players should be sent a summary now
     */
    public static synchronized boolean pushDue(long nowMillis) {
        long written = lastWriteMillis;
        if (written == 0L || nowMillis - lastPushMillis < PUSH_INTERVAL_MILLIS) {
            return false;
        }
        // Everything written before (last push - settle) had already settled when we last pushed, so
        // that push covered it. Only a later write can make a new answer.
        if (lastPushMillis != 0L && written <= lastPushMillis - CsLodStoreScan.SETTLE_MILLIS) {
            return false;
        }
        lastPushMillis = nowMillis;
        return true;
    }

    /** Forgets everything. Called when the server stops, so a new world starts with no history. */
    public static synchronized void reset() {
        lastWriteMillis = 0L;
        lastPushMillis = 0L;
    }
}
