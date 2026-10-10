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

package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.lod.net.CsLodRegionHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which store regions voxy already holds because the world's own server fed it every chunk of them.
 *
 * <p>Singleplayer only. The integrated server's voxy sink puts each chunk into voxy as it is
 * generated, and the same chunk goes into the CSLOD store. The download client then has nothing to
 * add for that region -- but it cannot tell, because the claims it keeps are only for what IT
 * injected. Without this, a session that stood the download down (mod_support #45) would leave the
 * next session's catch-up to re-send every region the pregen wrote: a finished 4096-block world-enter
 * pregen is ~2.4 GB, in-band, into a second copy on disk, then into voxy a second time.
 *
 * <p>So the server writes it down. A region counts only if EVERY chunk written to it this session
 * also reached voxy; one chunk voxy did not take (no engine yet, before the player logged in; voxy
 * refusing it) leaves the region to the catch-up. Recorded against the region file's freshness token
 * as it stands when the store closes, so any later write -- the next session's pregen, a crash that
 * skipped this step -- makes the entry stale and the region is caught up as before. Erring here
 * always errs towards sending again, never towards a hole.
 *
 * <p>One file per dimension directory, {@value #FILE}: a line per region, {@code rx rz token}.
 */
public final class LiveFeedLedger {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    public static final String FILE = "live-fed.ledger";

    /** dimension dir -> (region key -> fed so far this session). Main thread writes; finish reads. */
    private static final Map<Path, Map<Long, Boolean>> SESSION = new ConcurrentHashMap<>();

    private LiveFeedLedger() {
    }

    /**
     * Notes that a chunk was written to the store, and whether voxy took it too. Main thread.
     *
     * @param dimensionDir the store directory of the chunk's dimension
     * @param chunkX chunk x
     * @param chunkZ chunk z
     * @param fed true when the same chunk reached voxy
     */
    public static void touch(Path dimensionDir, int chunkX, int chunkZ, boolean fed) {
        Map<Long, Boolean> regions = SESSION.computeIfAbsent(dimensionDir, ignored -> new ConcurrentHashMap<>());
        regions.merge(key(chunkX >> 5, chunkZ >> 5), fed, (was, now) -> was && now);
    }

    /**
     * Writes this session down, once the store has been flushed and closed so the tokens are final.
     * Called from the LOD shutdown. A no-op where no voxy sink ever touched anything.
     */
    public static void finishAll() {
        for (Map.Entry<Path, Map<Long, Boolean>> dimension : SESSION.entrySet()) {
            try {
                finish(dimension.getKey(), dimension.getValue());
            } catch (IOException e) {
                // Not fatal: the catch-up will simply send those regions again next session.
                LOGGER.warn("Chunksmith: could not record which LOD regions voxy already holds in {}: {}",
                        dimension.getKey(), e.toString());
            }
        }
        SESSION.clear();
    }

    private static void finish(Path dir, Map<Long, Boolean> touched) throws IOException {
        Map<Long, Long> ledger = read(dir);
        for (Map.Entry<Long, Boolean> region : touched.entrySet()) {
            long key = region.getKey();
            Path file = dir.resolve("r." + regionX(key) + "." + regionZ(key) + ".cslod");
            if (!region.getValue() || !Files.isRegularFile(file)) {
                ledger.remove(key);
                continue;
            }
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            ledger.put(key, CsLodRegionHash.of(attrs.lastModifiedTime().toMillis(), attrs.size()));
        }
        List<String> lines = new ArrayList<>(ledger.size());
        for (Map.Entry<Long, Long> entry : ledger.entrySet()) {
            lines.add(regionX(entry.getKey()) + " " + regionZ(entry.getKey()) + " " + entry.getValue());
        }
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.write(tmp, lines, StandardCharsets.US_ASCII);
        Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * True when voxy was fed every chunk of this region, as the file stands now.
     *
     * @param ledger what {@link #read} returned for the region's dimension
     * @param regionX region x
     * @param regionZ region z
     * @param token the freshness token the server is advertising for it
     * @return true when the catch-up can skip it
     */
    public static boolean vouches(Map<Long, Long> ledger, int regionX, int regionZ, long token) {
        Long recorded = ledger.get(key(regionX, regionZ));
        return recorded != null && recorded == token;
    }

    /**
     * Reads a dimension's ledger. Missing or unreadable is an empty ledger, which vouches for nothing.
     *
     * @param dir the store directory of one dimension
     * @return region key to token
     */
    public static Map<Long, Long> read(Path dir) {
        Map<Long, Long> out = new HashMap<>();
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    out.put(key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), Long.parseLong(parts[2]));
                } catch (NumberFormatException ignored) {
                    // A damaged line vouches for nothing; that region is simply caught up again.
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: could not read {}: {}", file, e.toString());
        }
        return out;
    }

    static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private static int regionX(long key) {
        return (int) (key >> 32);
    }

    private static int regionZ(long key) {
        return (int) key;
    }
}
