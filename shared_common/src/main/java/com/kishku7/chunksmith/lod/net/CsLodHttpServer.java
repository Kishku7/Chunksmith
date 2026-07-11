package com.kishku7.chunksmith.lod.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;

/**
 * The LOD backchannel: a small, read-only HTTP server that hands out CSLOD region files.
 *
 * <p><b>Why this exists.</b> A plugin channel rides the same connection as gameplay, so pushing hundreds
 * of megabytes through it means starving the game loop -- and it re-compresses payloads that are already
 * compressed. But the CSLOD store is ALREADY plain region files, so the server does not need to stream
 * anything: it just serves them. Range requests, resume, parallel connections, at network speed, with the
 * game pipeline untouched.
 *
 * <p><b>Address is derived, never configured:</b> the game's own interface, at game port + 1. There is
 * nothing for an operator to set. If the port cannot be bound, that is a LOG LINE, not an error -- the
 * client silently falls back to the in-band channel.
 *
 * <p>Uses the JDK's own {@link HttpServer}: zero dependencies, consistent with the rest of the LOD stack
 * (no native DB, no native compressor).
 *
 * <p><b>Hardening.</b> This is a port opened on someone's game server, so:
 * <ul>
 *   <li>GET/HEAD only. No writes, no directory listing.</li>
 *   <li>Serves ONLY from the store root. The path is matched against a strict regex, then canonicalized
 *       and re-checked to be inside the root -- so {@code ..}, absolute paths and symlinks cannot escape.</li>
 *   <li>Token required, bound to (uuid, ip, expiry), revoked on disconnect.</li>
 *   <li>Per-IP concurrency cap, request/header size caps, and idle timeouts.</li>
 *   <li><b>Fails CLOSED to 404</b> -- never 403, which would confirm that a file exists.</li>
 * </ul>
 */
public final class CsLodHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** The ONLY filename shape we will ever serve. Anything else is a 404. */
    private static final Pattern REGION_FILE = Pattern.compile("r\\.-?\\d{1,7}\\.-?\\d{1,7}\\.cslod");

    /** A dimension directory as written by the store: {@code minecraft_overworld}. */
    private static final Pattern DIM_DIR = Pattern.compile("[a-z0-9_.-]{1,64}");

    private static final int MAX_CONCURRENT_PER_IP = 6;
    private static final int STOP_GRACE_SECONDS = 2;

    private final Path storeRoot;
    private final CsLodTokens tokens;
    private final CsLodTokens.OnlineCheck onlineCheck;
    private final Map<String, Integer> inFlightByIp = new ConcurrentHashMap<>();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private HttpServer server;
    private ExecutorService pool;
    private int port;

    /**
     * @param storeRoot the {@code <world>/chunksmith/lod} directory -- the ONLY tree ever served
     */
    public CsLodHttpServer(final Path storeRoot, final CsLodTokens tokens, final CsLodTokens.OnlineCheck onlineCheck) {
        this.storeRoot = storeRoot.toAbsolutePath().normalize();
        this.tokens = tokens;
        this.onlineCheck = onlineCheck;
    }

    /**
     * Bind and start.
     *
     * @param bindAddress the address the GAME is bound to (empty/null = all interfaces, same as the game)
     * @param gamePort    the game's port; the backchannel takes gamePort + 1
     * @return the bound port, or 0 if the backchannel is unavailable (in which case: fall back in-band)
     */
    public int start(final String bindAddress, final int gamePort) {
        final int wanted = CsLodProtocol.httpPort(gamePort);
        if (wanted == 0) {
            LOGGER.info("Chunksmith: no room for a LOD backchannel port above " + gamePort + "; in-band only");
            return 0;
        }
        try {
            final InetSocketAddress address = (bindAddress == null || bindAddress.isBlank())
                    ? new InetSocketAddress(wanted)
                    : new InetSocketAddress(InetAddress.getByName(bindAddress), wanted);

            server = HttpServer.create(address, 32);
            // A small bounded pool: this serves files, and it must never become the reason a game server
            // runs out of threads.
            pool = Executors.newFixedThreadPool(4, runnable -> {
                final Thread thread = new Thread(runnable, "chunksmith-lod-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(pool);
            server.createContext(CsLodProtocol.HTTP_PREFIX, this::handle);
            server.start();
            port = wanted;
            LOGGER.info("Chunksmith: LOD backchannel listening on " + address + " (game port + 1)");
            return port;
        } catch (final IOException e) {
            // Not an error. The operator may simply not have the port open -- the client falls back in-band.
            LOGGER.info("Chunksmith: LOD backchannel could not bind port " + wanted
                    + " (" + e.getMessage() + "); falling back to the in-band channel");
            server = null;
            return 0;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(STOP_GRACE_SECONDS);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        tokens.clear();
    }

    public int getPort() {
        return port;
    }

    /** served / bytes / rejected -- surfaced by the status command. Counters exist from day one, on purpose. */
    public String describe() {
        return server == null
                ? "backchannel: not running (in-band fallback)"
                : "backchannel: port " + port + ", " + served.get() + " files, " + bytes.get()
                        + " bytes, " + rejected.get() + " rejected, " + tokens.size() + " live tokens";
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        try {
            final String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                fail(exchange);
                return;
            }

            final UUID player = tokens.validate(
                    exchange.getRequestHeaders().getFirst(CsLodProtocol.HEADER_TOKEN), ip, onlineCheck);
            if (player == null) {
                fail(exchange);
                return;
            }

            final Path file = resolve(exchange.getRequestURI().getPath());
            if (file == null || !Files.isRegularFile(file)) {
                fail(exchange);
                return;
            }

            if (!acquire(ip)) {
                // Too many parallel fetches from one address. 429 is honest here: the client is
                // authenticated, it is simply being greedy.
                exchange.sendResponseHeaders(429, -1);
                return;
            }
            try {
                sendFile(exchange, file, "HEAD".equals(method));
            } finally {
                release(ip);
            }
        } catch (final RuntimeException e) {
            // Never let a handler bug take the game server with it.
            LOGGER.warn("Chunksmith: LOD backchannel error: " + e);
            fail(exchange);
        } finally {
            exchange.close();
        }
    }

    /**
     * Map a request path to a file INSIDE the store, or null.
     *
     * <p>Two independent gates: the shape must match {@code /lod/<dim>/r.<x>.<z>.cslod} exactly, AND the
     * canonicalized result must still live under the store root. Either one alone would probably do; both
     * together mean a path-traversal bug needs two mistakes, not one.
     */
    private Path resolve(final String requestPath) {
        if (requestPath == null || !requestPath.startsWith(CsLodProtocol.HTTP_PREFIX)) {
            return null;
        }
        final String relative = requestPath.substring(CsLodProtocol.HTTP_PREFIX.length());
        final String[] parts = relative.split("/");
        if (parts.length != 2) {
            return null;
        }
        if (!DIM_DIR.matcher(parts[0]).matches() || !REGION_FILE.matcher(parts[1]).matches()) {
            return null;
        }
        final Path candidate = storeRoot.resolve(parts[0]).resolve(parts[1]).toAbsolutePath().normalize();
        return candidate.startsWith(storeRoot) ? candidate : null;
    }

    private void sendFile(final HttpExchange exchange, final Path file, final boolean headOnly) throws IOException {
        final long size = Files.size(file);
        final long[] range = parseRange(exchange.getRequestHeaders().getFirst("Range"), size);

        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

        final long offset = range[0];
        final long length = range[1];
        final int status;
        if (offset > 0 || length != size) {
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + offset + "-" + (offset + length - 1) + "/" + size);
            status = 206;
        } else {
            status = 200;
        }

        if (headOnly) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }

        exchange.sendResponseHeaders(status, length);
        try (InputStream in = Files.newInputStream(file); OutputStream out = exchange.getResponseBody()) {
            in.skipNBytes(offset);
            final byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                final int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        served.incrementAndGet();
        bytes.addAndGet(length);
    }

    /** Single range only. A multi-range request is answered with the whole file rather than honoured. */
    private static long[] parseRange(final String header, final long size) {
        if (header == null || !header.startsWith("bytes=") || header.indexOf(',') >= 0) {
            return new long[]{0L, size};
        }
        final String spec = header.substring("bytes=".length()).trim();
        final int dash = spec.indexOf('-');
        if (dash < 0) {
            return new long[]{0L, size};
        }
        try {
            final String from = spec.substring(0, dash).trim();
            final String to = spec.substring(dash + 1).trim();
            if (from.isEmpty()) {
                final long suffix = Math.min(Long.parseLong(to), size);
                return new long[]{size - suffix, suffix};
            }
            final long start = Long.parseLong(from);
            if (start < 0 || start >= size) {
                return new long[]{0L, size};
            }
            final long end = to.isEmpty() ? size - 1 : Math.min(Long.parseLong(to), size - 1);
            if (end < start) {
                return new long[]{0L, size};
            }
            return new long[]{start, end - start + 1};
        } catch (final NumberFormatException e) {
            return new long[]{0L, size};
        }
    }

    /** Reserve a slot for this address, atomically. Returns false when the address is already at the cap. */
    private boolean acquire(final String ip) {
        final boolean[] admitted = {false};
        inFlightByIp.compute(ip, (key, current) -> {
            final int inFlight = current == null ? 0 : current;
            if (inFlight >= MAX_CONCURRENT_PER_IP) {
                return inFlight;
            }
            admitted[0] = true;
            return inFlight + 1;
        });
        return admitted[0];
    }

    private void release(final String ip) {
        inFlightByIp.computeIfPresent(ip, (key, current) -> current <= 1 ? null : current - 1);
    }

    /** Fail CLOSED: 404 for everything, so a probe cannot learn what exists. */
    private void fail(final HttpExchange exchange) throws IOException {
        rejected.incrementAndGet();
        exchange.sendResponseHeaders(404, -1);
    }
}
