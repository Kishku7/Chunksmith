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
import com.kishku7.chunksmith.lod.net.CsLodProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Pulls CSLOD region files from a Chunksmith server's HTTP
 * backchannel into the local store. Plain HTTP and plain files, so
 * it is testable without a game.
 *
 * <p><b>The local store IS the cache.</b> The server sends a
 * freshness token per region; we compare each against the token we
 * recorded when we stored our copy ({@link CsLodManifest}) and
 * download only what is missing or changed, so a re-join downloads
 * nothing. We do not re-hash our own files to find out: that read
 * the whole store on every index, and it was half of the bug that
 * killed the server.
 *
 * <p>{@link #cancel()} halts the flow immediately. Downloads run on
 * their own threads, in parallel, because the server proved it
 * serves them fairly: ~55 MB/s per client with no starvation, even
 * with another client hammering it at the same time.
 */
public final class CsLodDownloader {

    /** Parallel fetches. The server caps concurrency per IP; stay comfortably under it. */
    private static final int WORKERS = 4;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Path storeRoot;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile ExecutorService pool;

    /**
     * What the server said about each region we hold, for the
     * dimension of the current download. Opened at the top of {@link
     * #download}, written by the four fetch threads as regions land,
     * and saved once at the end -- one file write per download, not
     * one per region.
     */
    private volatile CsLodManifest manifest;

    /** @param storeRoot the client's own store, e.g. {@code .minecraft/chunksmith/lod/<server>/<dim>} */
    public CsLodDownloader(Path storeRoot) {
        this.storeRoot = storeRoot;
    }

    /**
     * Fetches everything in the index we do not already have.
     *
     * @param port  the backchannel port the server advertised (game port + 1)
     * @param token the token the server issued over the in-band channel
     * @param index what the server has, with a freshness token per region
     */
    public void download(final String host, final int port, final String token,
                         final CsLodMessages.RegionIndex index, final Consumer<String> progress) {
        cancelled.set(false);
        // The dimension came off the wire from a server we do not trust to be honest. Gate it before it
        // becomes a path, exactly as the in-band and cache consumers do. A "../.." here would write
        // region files outside the client's store. If it is malformed we refuse the whole transfer.
        Path dimDir = CsLodStore.dimensionDir(storeRoot, index.dimension());
        if (dimDir == null) {
            progress.accept("LOD: refusing a malformed dimension id from the server");
            return;
        }
        // What the server told us about the regions we already hold, not a CRC of our own bytes. See
        // CsLodManifest for the client-side half of the bug that killed the server.
        this.manifest = CsLodManifest.open(storeRoot, index.dimension());

        List<CsLodMessages.RegionEntry> wanted = index.regions().stream()
                .filter(entry -> {
                    if (haveAlready(dimDir, entry)) {
                        skipped.incrementAndGet();
                        return false;
                    }
                    return true;
                })
                .toList();

        // The server has already filtered its index down to what our announced radius can draw, so this
        // count is "regions within my radius", not "regions the server holds". Saying "on the server" here
        // hid a real bug: a voxy client asking for a 256-block radius was told "1 regions on the server"
        // when the server held nine.
        progress.accept("LOD: " + index.regions().size() + " regions within my radius, " + skipped.get()
                + " already cached, " + wanted.size() + " to fetch");
        if (wanted.isEmpty()) {
            return;
        }

        pool = Executors.newFixedThreadPool(WORKERS, runnable -> {
            Thread thread = new Thread(runnable, "chunksmith-lod-download");
            thread.setDaemon(true);
            return thread;
        });
        for (CsLodMessages.RegionEntry entry : wanted) {
            pool.submit(() -> {
                if (cancelled.get()) {
                    return;
                }
                try {
                    fetch(host, port, token, index.dimension(), entry);
                    long done = downloaded.incrementAndGet();
                    if (done % 25 == 0) {
                        progress.accept("LOD: fetched " + done + "/" + wanted.size()
                                + " regions (" + (bytes.get() / 1024 / 1024) + " MB)");
                    }
                } catch (IOException | InterruptedException e) {
                    failed.incrementAndGet();
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.MINUTES)) {
                progress.accept("LOD: download timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Record what we now hold once, after the transfer. A manifest we fail to write costs a re-download
        // next session and nothing else, so it is reported and not thrown.
        try {
            this.manifest.save();
        } catch (IOException e) {
            progress.accept("LOD: could not write the region manifest (" + e + "); these regions will be"
                    + " re-fetched next session");
        }
        progress.accept("LOD: done. " + downloaded.get() + " fetched, " + skipped.get() + " cached, "
                + failed.get() + " failed, " + (bytes.get() / 1024 / 1024) + " MB");
    }

    /** How many regions this run actually fetched. */
    public long fetched() {
        return downloaded.get();
    }

    /**
     * Returns how many regions this run failed to fetch. Nonzero
     * with {@link #fetched()} == 0 means the fast path is dead.
     */
    public long failed() {
        return failed.get();
    }

    /** Stop. Immediately. */
    public void cancel() {
        cancelled.set(true);
        ExecutorService current = pool;
        if (current != null) {
            current.shutdownNow();
        }
    }

    public long getDownloadedCount() {
        return downloaded.get();
    }

    public long getSkippedCount() {
        return skipped.get();
    }

    public long getFailedCount() {
        return failed.get();
    }

    public long getBytes() {
        return bytes.get();
    }

    /** requested / fetched / cached / failed. Counters exist from commit one, deliberately. */
    public String describe() {
        return "fetched " + downloaded.get() + ", cached " + skipped.get() + ", failed " + failed.get()
                + ", " + (bytes.get() / 1024 / 1024) + " MB";
    }

    private void fetch(final String host, final int port, final String token,
                       final String dimension, final CsLodMessages.RegionEntry entry)
            throws IOException, InterruptedException {
        // Re-gate here too: this is a distinct consumer of the wire dimension, so it validates rather than
        // trusting that the caller did (D20: harden every consumer, not one).
        Path dimDir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dimDir == null) {
            throw new IOException("refusing a malformed dimension id: " + dimension);
        }
        String name = "r." + entry.regionX() + "." + entry.regionZ() + ".cslod";
        URI uri = URI.create("http://" + host + ":" + port + CsLodProtocol.HTTP_PREFIX
                + dimension + "/" + name);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header(CsLodProtocol.HEADER_TOKEN, token)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + name);
        }

        // Write to a temp file and move into place, so a half-finished download can never be mistaken for
        // a cached region on the next join.
        Path target = dimDir.resolve(name);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(name + ".part");
        try (InputStream in = response.body()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        long stored = Files.size(target);
        bytes.addAndGet(stored);

        // The region is on disk. Record the server's token and the size we actually received rather than the
        // advertised one, so a short or padded transfer shows as a mismatch next check instead of caching good.
        this.manifest.put(entry.regionX(), entry.regionZ(), entry.hash(), stored);
    }

    /**
     * Checks whether we already hold what the server is advertising.
     * A manifest lookup and one stat. See {@link CsLodManifest}.
     * Since beta-4 this no longer reads the client's own store.
     */
    private boolean haveAlready(Path dimDir, CsLodMessages.RegionEntry entry) {
        CsLodManifest current = this.manifest;
        return current != null && current.holds(dimDir, entry);
    }
}
