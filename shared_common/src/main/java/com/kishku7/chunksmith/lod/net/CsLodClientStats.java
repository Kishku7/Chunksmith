/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
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
 * What the server knows about each connected player's LOD client, for {@code /cs lod clients}.
 *
 * <p>Platform-blind and keyed by player id: the loaders and the Bukkit plugin each feed it from their own
 * hello and message handlers, and read it back to print a line per online player. It holds nothing
 * persistent; a player's entry goes when they disconnect and the whole table goes when the server stops.
 *
 * <p>Thread-safe: the hello and report handlers run on the main thread, the transfer counts come from the
 * backchannel's handler threads, and the command reads from the main thread.
 */
public final class CsLodClientStats {

    private static final Map<UUID, Stats> TABLE = new ConcurrentHashMap<>();

    private CsLodClientStats() {
    }

    /** One player's numbers. Fields are guarded by the instance; read them through {@link #snapshot}. */
    public static final class Stats {
        private int protocol;
        private boolean voxy;
        private boolean dh;
        private int radiusBlocks;
        private int backchannelPort;
        private long backchannelFiles;
        private long backchannelBytes;
        private long inBandRequests;
        private long inBandRegions;
        private long injectedChunks;
        private long injectedFailed;
        private long injectionRuns;
        private String lastRenderer = "";
        private long lastInjectionMillis;

        private Stats() {
        }
    }

    /** An immutable copy of one player's numbers, safe to format at leisure. */
    public record Snapshot(int protocol, boolean voxy, boolean dh, int radiusBlocks, int backchannelPort,
                           long backchannelFiles, long backchannelBytes, long inBandRequests, long inBandRegions,
                           long injectedChunks, long injectedFailed, long injectionRuns, String lastRenderer,
                           long lastInjectionMillis) {
    }

    private static Stats of(UUID player) {
        return TABLE.computeIfAbsent(player, id -> new Stats());
    }

    /** A client said hello: record what it has and what port we pointed it at (0 = in-band only). */
    public static void onHello(UUID player, int protocol, boolean voxy, boolean dh, int radiusBlocks,
                               int backchannelPort) {
        Stats stats = of(player);
        synchronized (stats) {
            stats.protocol = protocol;
            stats.voxy = voxy;
            stats.dh = dh;
            stats.radiusBlocks = radiusBlocks;
            stats.backchannelPort = backchannelPort;
        }
    }

    /** A region file went to this player over the backchannel. */
    public static void addBackchannel(UUID player, long bytes) {
        Stats stats = TABLE.get(player);
        if (stats == null) {
            // Never said hello, or already left: a late count must not resurrect a gone player's entry.
            return;
        }
        synchronized (stats) {
            stats.backchannelFiles++;
            stats.backchannelBytes += bytes;
        }
    }

    /** This player asked for regions in-band, the slow path. */
    public static void addInBand(UUID player, int regions) {
        Stats stats = TABLE.get(player);
        if (stats == null) {
            // Never said hello, or already left: a late count must not resurrect a gone player's entry.
            return;
        }
        synchronized (stats) {
            stats.inBandRequests++;
            stats.inBandRegions += Math.max(0, regions);
        }
    }

    /** This player's client reported a finished injection batch. */
    public static void addInjected(UUID player, String renderer, int chunks, int failed) {
        Stats stats = TABLE.get(player);
        if (stats == null) {
            // Never said hello, or already left: a late count must not resurrect a gone player's entry.
            return;
        }
        synchronized (stats) {
            stats.injectionRuns++;
            stats.injectedChunks += chunks;
            stats.injectedFailed += failed;
            stats.lastRenderer = renderer == null ? "" : renderer;
            stats.lastInjectionMillis = System.currentTimeMillis();
        }
    }

    /** The player left. */
    public static void clear(UUID player) {
        TABLE.remove(player);
    }

    /** The server stopped. */
    public static void clearAll() {
        TABLE.clear();
    }

    /** @return this player's numbers, or null if they have never said hello (a vanilla client) */
    public static Snapshot snapshot(UUID player) {
        Stats stats = TABLE.get(player);
        if (stats == null) {
            return null;
        }
        synchronized (stats) {
            return new Snapshot(stats.protocol, stats.voxy, stats.dh, stats.radiusBlocks, stats.backchannelPort,
                    stats.backchannelFiles, stats.backchannelBytes, stats.inBandRequests, stats.inBandRegions,
                    stats.injectedChunks, stats.injectedFailed, stats.injectionRuns, stats.lastRenderer,
                    stats.lastInjectionMillis);
        }
    }

    /**
     * The lines for one player in {@code /cs lod clients}. Pure, so it is testable. {@code snapshot} is null
     * for a player whose client never said hello, which is the normal state of a vanilla client.
     */
    public static String[] describe(String name, Snapshot snapshot, long nowMillis) {
        if (snapshot == null) {
            return new String[] {name + ": no Chunksmith LOD client (vanilla, or it has not said hello)"};
        }
        String renderers = (snapshot.dh() ? "dh" : "") + (snapshot.dh() && snapshot.voxy() ? "+" : "")
                + (snapshot.voxy() ? "voxy" : "");
        if (renderers.isEmpty()) {
            renderers = "no renderer";
        }
        String path = snapshot.backchannelPort() == 0 ? "in-band only"
                : "backchannel port " + snapshot.backchannelPort();
        String line1 = name + ": LOD protocol v" + snapshot.protocol() + ", " + renderers + ", radius "
                + snapshot.radiusBlocks() + " blocks, " + path;
        String line2 = "    sent: " + snapshot.backchannelFiles() + " file" + (snapshot.backchannelFiles() == 1 ? "" : "s")
                + " (" + size(snapshot.backchannelBytes()) + ") by backchannel, " + snapshot.inBandRegions()
                + " region" + (snapshot.inBandRegions() == 1 ? "" : "s") + " in-band";
        String line3;
        if (snapshot.injectionRuns() == 0) {
            line3 = "    injected: nothing reported yet (the client may be older than 4.9.0, or still downloading)";
        } else {
            long ago = Math.max(0L, (nowMillis - snapshot.lastInjectionMillis()) / 1000L);
            line3 = "    injected: " + snapshot.injectedChunks() + " chunk" + (snapshot.injectedChunks() == 1 ? "" : "s")
                    + " into " + snapshot.lastRenderer() + " over " + snapshot.injectionRuns() + " batch"
                    + (snapshot.injectionRuns() == 1 ? "" : "es") + (snapshot.injectedFailed() > 0
                            ? ", " + snapshot.injectedFailed() + " failed" : "") + ", last " + ago + "s ago";
        }
        return new String[] {line1, line2, line3};
    }

    private static String size(long bytes) {
        return bytes >= 1024L * 1024L
                ? String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                : String.format("%.1f KB", bytes / 1024.0);
    }
}
