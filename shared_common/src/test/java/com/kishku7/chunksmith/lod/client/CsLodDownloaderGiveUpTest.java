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

import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * mod_support #48: when the backchannel is dead the downloader must stop after the first batch, not try
 * every region, so the in-band fallback starts in about a minute instead of an hour. A busy server
 * (429) is not a dead one and must not trigger it.
 */
public class CsLodDownloaderGiveUpTest {

    private static final int REGIONS = 40;

    private Path root;
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("cslod-giveup");
    }

    @After
    public void tearDown() throws IOException {
        if (server != null) {
            server.stop(0);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    public void deadBackchannelStopsAfterTheFirstBatch() throws IOException {
        int port = serve(500);
        List<String> lines = new ArrayList<>();
        CsLodDownloader downloader = new CsLodDownloader(root);
        downloader.download("127.0.0.1", port, "t", index(), lines::add);

        assertTrue(downloader.gaveUp());
        assertFalse(downloader.wasCancelled());
        assertEquals(0, downloader.fetched());
        assertTrue("failed " + downloader.failed(), downloader.failed() > 0);
        // The first batch, plus at most the few already in flight when it gave up -- never all of them.
        assertTrue("requests " + requests.get(), requests.get() < REGIONS / 2);
        assertTrue(String.join(" | ", lines), lines.stream().anyMatch(l -> l.contains("skipping the rest")));
        assertTrue(String.join(" | ", lines), lines.stream().anyMatch(l -> l.contains("first download failure")
                && l.contains("HTTP 500")));
    }

    @Test
    public void busyServerIsNotGivenUpOn() throws IOException {
        int port = serve(429);
        CsLodDownloader downloader = new CsLodDownloader(root);
        downloader.download("127.0.0.1", port, "t", index(), line -> { });

        assertFalse(downloader.gaveUp());
        assertEquals(REGIONS, requests.get());
        assertEquals(REGIONS, downloader.failed());
    }

    @Test
    public void requestsInFlightAtTheGiveUpAreStoppedNotWaitedOut() throws IOException {
        // The first four answer 500; everything after that hangs. Without stopping the in-flight
        // requests, download() would sit out their full timeout (30 s here) before returning.
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            if (n == 4) {
                // The deciding failure lands late, so the other three workers have certainly moved on to
                // regions that hang by the time the give-up happens.
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (n <= 4) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        CsLodDownloader downloader = new CsLodDownloader(root);
        downloader.requestTimeout = java.time.Duration.ofSeconds(30);

        long started = System.nanoTime();
        downloader.download("127.0.0.1", server.getAddress().getPort(), "t", index(), line -> { });
        long tookMillis = (System.nanoTime() - started) / 1_000_000L;
        never.countDown();

        assertTrue(downloader.gaveUp());
        assertTrue("download() took " + tookMillis + " ms", tookMillis < 10_000L);
    }

    @Test
    public void giveUpRule() {
        assertFalse(CsLodDownloader.shouldGiveUp(3, 0));
        assertTrue(CsLodDownloader.shouldGiveUp(4, 0));
        assertFalse("one region arrived, so the path works", CsLodDownloader.shouldGiveUp(50, 1));
    }

    private int serve(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static CsLodMessages.RegionIndex index() {
        List<CsLodMessages.RegionEntry> regions = new ArrayList<>();
        for (int i = 0; i < REGIONS; i++) {
            regions.add(new CsLodMessages.RegionEntry(i, 0, 1000L + i, 4096L));
        }
        return new CsLodMessages.RegionIndex("minecraft_overworld", regions);
    }
}
