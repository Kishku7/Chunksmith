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
 * <p><b>Why this exists.</b> A plugin channel rides the same connection as gameplay, so pushing hundreds of
 * megabytes through it starves the game loop, and it re-compresses payloads that are already compressed.
 * The CSLOD store is already plain region files, so the server does not stream anything: it serves them,
 * with range requests, resume and parallel connections, and the game pipeline untouched.
 *
 * <p><b>The address follows the game; the port is the operator's if they want it.</b> The interface is
 * always the one the game is bound to; a client is already connected to that host. The port defaults to
 * game port + 1 and can be set explicitly, because a managed host rents a fixed set of ports and will not
 * hand out the one next to the game just because it is tidy (mod_support #19). If it cannot be bound the mod
 * still runs (the client falls back in-band) but it says so at WARN, naming the port.
 *
 * <p>Uses the JDK's own {@link HttpServer}: zero dependencies, consistent with the rest of the LOD stack
 * (no native DB, no native compressor).
 *
 * <p><b>Hardening.</b> This is a port opened on someone's game server, so:
 * <ul>
 *   <li>GET/HEAD only. No writes, no directory listing.</li>
 *   <li>Serves only from the store root: a strict regex on the path, then canonicalize and re-check it is
 *       inside the root, so {@code ..}, absolute paths and symlinks cannot escape.</li>
 *   <li>Token required, bound to (uuid, ip, expiry), revoked on disconnect.</li>
 *   <li>Per-IP concurrency cap, request/header size caps, and idle timeouts.</li>
 *   <li><b>Fails closed to 404</b> rather than 403, which would confirm that a file exists.</li>
 * </ul>
 */
public final class CsLodHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** The only filename shape we will ever serve. Anything else is a 404. */
    private static final Pattern REGION_FILE = Pattern.compile("r\\.-?\\d{1,7}\\.-?\\d{1,7}\\.cslod");

    /** A dimension directory as written by the store: {@code minecraft_overworld}. */
    private static final Pattern DIM_DIR = Pattern.compile("[a-z0-9_.-]{1,64}");

    private static final int MAX_CONCURRENT_PER_IP = 6;
    private static final int STOP_GRACE_SECONDS = 2;

    /**
     * Where a given dimension's region files live. A mod-loader server keeps every dimension under one save
     * root, so the default is {@code root.resolve(dimension)}; Bukkit gives each world its own folder, so
     * there is no single parent to point at and it supplies its own resolver.
     */
    @FunctionalInterface
    public interface RootResolver {
        /** @return the directory holding that dimension's {@code .cslod} files, or null if unknown */
        Path rootFor(String dimension);
    }

    private final RootResolver roots;
    private final CsLodTokens tokens;
    private final CsLodTokens.OnlineCheck onlineCheck;
    private final Map<String, Integer> inFlightByIp = new ConcurrentHashMap<>();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private HttpServer server;
    private ExecutorService pool;
    private int port;
    private boolean derived = true;

    /** @param storeRoot the {@code <world>/chunksmith/lod} directory; dimensions are its subdirectories */
    public CsLodHttpServer(Path storeRoot, CsLodTokens tokens, CsLodTokens.OnlineCheck onlineCheck) {
        this(dimension -> storeRoot.toAbsolutePath().normalize().resolve(dimension),
                tokens, onlineCheck);
    }

    /** @param roots resolves a dimension name to the directory holding its region files */
    public CsLodHttpServer(final RootResolver roots, final CsLodTokens tokens,
                           final CsLodTokens.OnlineCheck onlineCheck) {
        this.roots = roots;
        this.tokens = tokens;
        this.onlineCheck = onlineCheck;
    }

    /**
     * Bind and start.
     *
     * @param bindAddress    the address the game is bound to (empty/null = all interfaces, same as the game)
     * @param configuredPort the operator's chosen port, or 0 to derive {@code gamePort + 1}
     * @return the bound port, or 0 if the backchannel is unavailable (in which case: fall back in-band)
     */
    public int start(String bindAddress, int gamePort, int configuredPort) {
        derived = configuredPort == 0;
        final int wanted = CsLodProtocol.httpPort(gamePort, configuredPort);
        if (wanted == 0) {
            if (derived) {
                LOGGER.warn("Chunksmith: no room for a LOD backchannel port above " + gamePort
                        + "; falling back to the in-band channel (slower). Set lodBackchannelPort"
                        + " to name a port explicitly.");
            } else {
                // Refused before we ever tried to bind, so say which rule refused it. An operator who
                // typed their game port here would otherwise get a bind failure with no cause.
                LOGGER.warn("Chunksmith: lodBackchannelPort " + configuredPort + " cannot be used"
                        + (configuredPort == gamePort
                                ? " because it is the game's own port"
                                : " because it is outside 1024-65535")
                        + "; falling back to the in-band channel (slower)");
            }
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
            LOGGER.info("Chunksmith: LOD backchannel listening on " + address + " (port " + port
                    + (derived ? ", derived from the game port" : ", set by lodBackchannelPort")
                    + "). Open this port to clients for fast LOD downloads.");
            return port;
        } catch (IOException e) {
            // The mod still works (the client falls back in-band) but this is the line that answers
            // "why is no LOD arriving?", so it is a WARN and it names the port. At INFO it was read by
            // nobody and the symptom looked like a broken mod instead of a closed port.
            LOGGER.warn("Chunksmith: the LOD backchannel could not bind port " + wanted
                    + " (" + e.getMessage() + "). Falling back to the in-band channel, which works but is"
                    + " much slower. Either open port " + wanted + ", or set lodBackchannelPort to a port"
                    + " your host does give you (/cs set lodBackchannelPort <port>).");
            server = null;
            port = 0;
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

    /** True when the bound port came from {@code gamePort + 1} rather than from the config. */
    public boolean isDerived() {
        return derived;
    }

    /** served / bytes / rejected -- surfaced by the status command. Counters exist from day one, on purpose. */
    public String describe() {
        return server == null
                ? "backchannel: not running (in-band fallback)"
                : "backchannel: port " + port + (derived ? " (derived)" : " (configured)")
                        + ", " + served.get() + " files, " + bytes.get()
                        + " bytes, " + rejected.get() + " rejected, " + tokens.size() + " live tokens";
    }

    private void handle(HttpExchange exchange) throws IOException {
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
        } catch (RuntimeException e) {
            // Never let a handler bug take the game server with it.
            LOGGER.warn("Chunksmith: LOD backchannel error: " + e);
            fail(exchange);
        } finally {
            exchange.close();
        }
    }

    /**
     * Map a request path to a file inside the store, or null. Two independent gates: the shape must match
     * {@code /lod/<dim>/r.<x>.<z>.cslod} exactly, and the canonicalized result must still live under the
     * store root. Either alone would probably do; both are cheap.
     */
    private Path resolve(String requestPath) {
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
        // Resolve the dimension first, then containment-check against that dimension's own root. Both
        // gates are unchanged in strength.
        final Path dimensionRoot = roots.rootFor(parts[0]);
        if (dimensionRoot == null) {
            return null;
        }
        final Path base = dimensionRoot.toAbsolutePath().normalize();
        final Path candidate = base.resolve(parts[1]).toAbsolutePath().normalize();
        return candidate.startsWith(base) ? candidate : null;
    }

    private void sendFile(HttpExchange exchange, Path file, boolean headOnly) throws IOException {
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
    private static long[] parseRange(String header, long size) {
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
        } catch (NumberFormatException e) {
            return new long[]{0L, size};
        }
    }

    /** Reserve a slot for this address, atomically. Returns false when the address is already at the cap. */
    private boolean acquire(String ip) {
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

    private void release(String ip) {
        inFlightByIp.computeIfPresent(ip, (key, current) -> current <= 1 ? null : current - 1);
    }

    /** Fail closed: 404 for everything, so a probe cannot learn what exists. */
    private void fail(HttpExchange exchange) throws IOException {
        rejected.incrementAndGet();
        exchange.sendResponseHeaders(404, -1);
    }
}
