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
 * Pulls CSLOD region files from a Chunksmith server's HTTP backchannel into the local store.
 *
 * <p>MC-agnostic on purpose: this is plain HTTP and plain files, so it can be tested without a game.
 *
 * <p><b>The local store IS the cache.</b> The server sends a region index with a freshness token per region;
 * we compare each against the token we RECORDED when we stored our copy ({@link CsLodManifest}) and download
 * only what is missing or has changed. A re-join downloads nothing. We do not re-hash our own files to find
 * out -- that read the whole store on every index, and it was half of the bug that killed the server.
 *
 * <p><b>The client drives, and the client can stop.</b> {@link #cancel()} halts the flow immediately --
 * an operator-hostile download that cannot be stopped is a bug, not a feature.
 *
 * <p>Runs on its own threads. Downloads are parallel because the server proved it serves them fairly:
 * ~55 MB/s per client with no starvation, even with another client hammering it at the same time.
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
     * What the server said about each region we hold, for the dimension of the current download.
     *
     * <p>Opened at the top of {@link #download}, written by the four fetch threads as regions land, and
     * saved once at the end -- one file write per download, not one per region.
     */
    private volatile CsLodManifest manifest;

    /**
     * @param storeRoot the CLIENT's own store, e.g. {@code .minecraft/chunksmith/lod/<server>/<dim>}
     */
    public CsLodDownloader(final Path storeRoot) {
        this.storeRoot = storeRoot;
    }

    /**
     * Fetch everything in the index we do not already have.
     *
     * @param host    the server we are connected to
     * @param port    the backchannel port the server advertised (game port + 1)
     * @param token   the token the server issued over the in-band channel
     * @param index   what the server has, with a content hash per region
     * @param progress human-readable progress
     */
    public void download(final String host, final int port, final String token,
                         final CsLodMessages.RegionIndex index, final Consumer<String> progress) {
        cancelled.set(false);
        // The dimension came off the wire from a server we do not trust to be honest. Gate it BEFORE it
        // becomes a path, exactly as the in-band and cache consumers do -- a "../.." here would write
        // region files outside the client's store. If it is malformed we refuse the whole transfer.
        final Path dimDir = CsLodStore.dimensionDir(storeRoot, index.dimension());
        if (dimDir == null) {
            progress.accept("LOD: refusing a malformed dimension id from the server");
            return;
        }
        // What the server told us about the regions we already hold. This -- not a CRC of our own bytes --
        // is the cache check now. See CsLodManifest: the old check read every region file in the radius off
        // the client's disk, on every index, which was the client-side half of the same bug that killed the
        // server.
        this.manifest = CsLodManifest.open(storeRoot, index.dimension());

        final List<CsLodMessages.RegionEntry> wanted = index.regions().stream()
                .filter(entry -> {
                    if (haveAlready(dimDir, entry)) {
                        skipped.incrementAndGet();
                        return false;
                    }
                    return true;
                })
                .toList();

        // The server has ALREADY filtered its index down to what our announced radius can draw, so this
        // count is "regions within my radius", NOT "regions the server holds". Saying "on the server" here
        // hid a real bug: a voxy client asking for a 256-block radius was told "1 regions on the server"
        // when the server held nine.
        progress.accept("LOD: " + index.regions().size() + " regions within my radius, " + skipped.get()
                + " already cached, " + wanted.size() + " to fetch");
        if (wanted.isEmpty()) {
            return;
        }

        pool = Executors.newFixedThreadPool(WORKERS, runnable -> {
            final Thread thread = new Thread(runnable, "chunksmith-lod-download");
            thread.setDaemon(true);
            return thread;
        });
        for (final CsLodMessages.RegionEntry entry : wanted) {
            pool.submit(() -> {
                if (cancelled.get()) {
                    return;
                }
                try {
                    fetch(host, port, token, index.dimension(), entry);
                    final long done = downloaded.incrementAndGet();
                    if (done % 25 == 0) {
                        progress.accept("LOD: fetched " + done + "/" + wanted.size()
                                + " regions (" + (bytes.get() / 1024 / 1024) + " MB)");
                    }
                } catch (final IOException | InterruptedException e) {
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
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Record what we now hold, ONCE, after the transfer -- not once per region. A manifest we fail to
        // write costs us a re-download next session and nothing else, so it is reported and not thrown.
        try {
            this.manifest.save();
        } catch (final IOException e) {
            progress.accept("LOD: could not write the region manifest (" + e + "); these regions will be"
                    + " re-fetched next session");
        }
        progress.accept("LOD: done -- " + downloaded.get() + " fetched, " + skipped.get() + " cached, "
                + failed.get() + " failed, " + (bytes.get() / 1024 / 1024) + " MB");
    }

    /** How many regions this run actually fetched. */
    public long fetched() {
        return downloaded.get();
    }

    /** How many regions this run failed to fetch. Nonzero with {@link #fetched()} == 0 means the fast path is dead. */
    public long failed() {
        return failed.get();
    }

    /** Stop. Immediately. */
    public void cancel() {
        cancelled.set(true);
        final ExecutorService current = pool;
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

    /** requested / fetched / cached / failed -- counters exist from commit one, deliberately. */
    public String describe() {
        return "fetched " + downloaded.get() + ", cached " + skipped.get() + ", failed " + failed.get()
                + ", " + (bytes.get() / 1024 / 1024) + " MB";
    }

    private void fetch(final String host, final int port, final String token,
                       final String dimension, final CsLodMessages.RegionEntry entry)
            throws IOException, InterruptedException {
        // Re-gate here too: this is a distinct consumer of the wire dimension, so it validates rather than
        // trusting that the caller did (D20 -- harden every consumer, not one).
        final Path dimDir = CsLodStore.dimensionDir(storeRoot, dimension);
        if (dimDir == null) {
            throw new IOException("refusing a malformed dimension id: " + dimension);
        }
        final String name = "r." + entry.regionX() + "." + entry.regionZ() + ".cslod";
        final URI uri = URI.create("http://" + host + ":" + port + CsLodProtocol.HTTP_PREFIX
                + dimension + "/" + name);

        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header(CsLodProtocol.HEADER_TOKEN, token)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        final HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + name);
        }

        // Write to a temp file and move into place, so a half-finished download can never be mistaken for
        // a cached region on the next join.
        final Path target = dimDir.resolve(name);
        Files.createDirectories(target.getParent());
        final Path temp = target.resolveSibling(name + ".part");
        try (InputStream in = response.body()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        final long stored = Files.size(target);
        bytes.addAndGet(stored);

        // The region is on disk. Record what the SERVER said about it -- its freshness token and the length
        // it claimed -- because that is the only thing we can compare against the next index. We record the
        // size we ACTUALLY received rather than the advertised one, so a short or padded transfer shows up as
        // a mismatch on the next check instead of being cached as good.
        this.manifest.put(entry.regionX(), entry.regionZ(), entry.hash(), stored);
    }

    /**
     * Do we already hold exactly what the server is advertising? A manifest lookup and one stat -- see
     * {@link CsLodManifest}. This is what makes a re-join free, and (since beta-4) what stops the client
     * reading its own store to find out.
     */
    private boolean haveAlready(final Path dimDir, final CsLodMessages.RegionEntry entry) {
        final CsLodManifest current = this.manifest;
        return current != null && current.holds(dimDir, entry);
    }
}
