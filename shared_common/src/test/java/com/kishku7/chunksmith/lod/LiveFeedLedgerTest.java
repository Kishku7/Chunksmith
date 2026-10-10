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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The ledger may only ever err towards sending a region again. A region it vouches for wrongly is a
 * hole in the player's distant terrain that nothing will ever fill.
 */
public class LiveFeedLedgerTest {

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("cs-ledger");
        LiveFeedLedger.finishAll();
    }

    @After
    public void tearDown() throws IOException {
        LiveFeedLedger.finishAll();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private Path region(int rx, int rz, int bytes) throws IOException {
        Path file = dir.resolve("r." + rx + "." + rz + ".cslod");
        Files.write(file, new byte[bytes]);
        return file;
    }

    private static long token(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return CsLodRegionHash.of(attrs.lastModifiedTime().toMillis(), attrs.size());
    }

    @Test
    public void aRegionVoxyGotEveryChunkOfIsVouchedFor() throws IOException {
        Path file = region(0, 0, 100);
        LiveFeedLedger.touch(dir, 1, 2, true);
        LiveFeedLedger.touch(dir, 31, 31, true);
        LiveFeedLedger.finishAll();
        assertTrue(LiveFeedLedger.vouches(LiveFeedLedger.read(dir), 0, 0, token(file)));
    }

    @Test
    public void oneChunkVoxyMissedLeavesTheRegionToTheCatchUp() throws IOException {
        Path file = region(0, 0, 100);
        LiveFeedLedger.touch(dir, 1, 2, true);
        LiveFeedLedger.touch(dir, 3, 4, false);   // before login: no engine yet
        LiveFeedLedger.touch(dir, 5, 6, true);
        LiveFeedLedger.finishAll();
        assertFalse(LiveFeedLedger.vouches(LiveFeedLedger.read(dir), 0, 0, token(file)));
    }

    @Test
    public void aLaterWriteMakesTheEntryStale() throws IOException {
        Path file = region(-1, 2, 100);
        LiveFeedLedger.touch(dir, -1, 64, true);   // chunk (-1, 64) is region (-1, 2)
        LiveFeedLedger.finishAll();
        assertTrue(LiveFeedLedger.vouches(LiveFeedLedger.read(dir), -1, 2, token(file)));
        // Next session writes more to it, with no ledger update (say, a crash).
        Files.write(file, new byte[200]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
        assertFalse(LiveFeedLedger.vouches(LiveFeedLedger.read(dir), -1, 2, token(file)));
    }

    @Test
    public void aTaintedSessionRemovesAnEarlierVouch() throws IOException {
        Path file = region(0, 0, 100);
        LiveFeedLedger.touch(dir, 0, 0, true);
        LiveFeedLedger.finishAll();
        LiveFeedLedger.touch(dir, 0, 1, false);
        LiveFeedLedger.finishAll();
        Map<Long, Long> ledger = LiveFeedLedger.read(dir);
        assertFalse(LiveFeedLedger.vouches(ledger, 0, 0, token(file)));
    }

    @Test
    public void noFileOrADamagedOneVouchesForNothing() throws IOException {
        Path file = region(0, 0, 100);
        assertFalse(LiveFeedLedger.vouches(LiveFeedLedger.read(dir), 0, 0, token(file)));
        Files.writeString(dir.resolve(LiveFeedLedger.FILE), "0 0 not-a-number\ngarbage\n");
        assertFalse(LiveFeedLedger.vouches(LiveFeedLedger.read(dir), 0, 0, token(file)));
    }
}
