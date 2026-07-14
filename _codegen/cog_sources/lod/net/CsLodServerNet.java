package com.kishku7.chunksmith.lod.net;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of the Chunksmith LOD protocol.
 *
 * <p>The client PULLS: it says hello (telling us which renderers it actually has), asks for a region
 * index, works out what it is missing, and fetches it -- over the HTTP backchannel when that is
 * available, in-band when it is not. It can stop at any time. The server never pushes uninvited.
 *
 * <p>Refuses a client with no renderer: there is no point burning a server's bandwidth on a player who
 * cannot draw the result.
 *
 * <p>Loader-blind: every wire call goes through {@link CsLodChannel}, the one per-loader/per-era seam
 * (Fabric raw channel &lt;1.20.2, Fabric payload registry, NeoForge PayloadRegistrar, Forge
 * SimpleChannel). The PROTOCOL itself is identical on every cell -- CsLodProtocol / CsLodMessages /
 * CsLodTokens / CsLodHttpServer all live in shared_common and never see a Minecraft type.
 *
 * <h2>Nothing in here reads a region file, and nothing in here touches a disk on the tick thread</h2>
 *
 * <p>Both of those were true in 3.1.0-beta-3 and both were wrong, and together they are what took a live
 * production server to 100% RAM and hung its shutdown for 67 minutes. {@code index()} ran on the server main
 * thread (its own javadoc said so) and called a {@code hash()} that did
 * {@code crc.update(Files.readAllBytes(file))} on EVERY region file inside the client's radius. On a
 * 340-region / 1567 MB store that is 366.9 MB read and allocated per index request, every one of those
 * byte[] a G1-humongous allocation straight into old gen -- and the client re-asks every five seconds
 * while the player travels. ~73 MB/s of humongous garbage, on the tick thread, competing with a pregen for
 * the same disk. Old gen filled, the concurrent cycle could not keep up, and the heap thrashed until
 * {@code saveAllChunks} could not allocate.
 *
 * <p>Three things changed, and they are load-bearing together:
 * <ol>
 *   <li><b>The freshness token no longer comes from the bytes.</b> It is derived from (mtime, size) --
 *       {@link CsLodRegionHash}, which explains at length why that answers the only question the client is
 *       actually asking. One {@code statx} per region, no reads, no allocation.</li>
 *   <li><b>The scan is off the main thread.</b> Even with a free token, a scan is a readdir plus a stat per
 *       region, and nothing about that needs the tick. The main thread now does one thing: take a snapshot
 *       of who is asking, where they are standing and what they can draw ({@link Request}), and hand it to
 *       the scan thread. The reply hops back to the main thread to be sent.</li>
 *   <li><b>The answer is bounded in BYTES, not just in regions.</b> {@link #MAX_REGIONS_PER_REQUEST} alone
 *       was never a bound on anything that mattered -- 4096 regions x 7 MB is ~28 GB.</li>
 * </ol>
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodServerNet {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static final CsLodTokens TOKENS = new CsLodTokens();

    /**
     * SERVER POLICY caps on what one client may ask for. Not wire constants -- they live here rather than
     * in CsLodProtocol because they are this server's limits, not part of the format.
     *
     * <p>Every number below arrives FROM THE NETWORK, from a player who has authenticated but is otherwise
     * untrusted. An unbounded region count would be handed straight to {@code new ArrayList<>(count)},
     * which allocates that array immediately -- a one-packet OOM; a negative one throws out of the tick
     * task. An unbounded radius would hand back an index of the entire store, however large it is.
     */
    private static final int MAX_REGIONS_PER_REQUEST = 4096;

    /**
     * And a cap on the BYTES an index may describe. This is the bound that was missing.
     *
     * <p>{@link #MAX_REGIONS_PER_REQUEST} bounds the COUNT, which is not the quantity anyone cares about:
     * a CSLOD region on a real store averages 4.6 MB and reaches 7.4 MB, so "at most 4096 regions" is "at
     * most about 28 GB" -- a limit that has never once been the binding constraint on anything. It did not
     * bound the old index's reads (that was the bug), and it does not bound what we are telling a client to
     * go and download now.
     *
     * <p>2 GiB. No LOD renderer draws two gigabytes of terrain from where it is standing, so this cannot
     * refuse an honest client; what it refuses is the pathological one -- a maximum-radius request against a
     * fully-pregenerated world -- and even then it refuses it POLITELY: the index is sorted NEAREST FIRST
     * and truncated, so the client is handed the terrain closest to it, which is the terrain it can see. It
     * re-asks as it travels and the rest arrives. A truncated index is late, never lossy.
     */
    private static final long MAX_INDEX_BYTES = 2L * 1024L * 1024L * 1024L;

    /** ~16k blocks: further than any LOD renderer draws, and it bounds the index we build. */
    private static final int MAX_RADIUS_BLOCKS = 16384;

    /**
     * A wire dimension id is a single store SUBDIRECTORY name and nothing else. It arrives from the
     * network, so it is validated exactly the way the HTTP backchannel validates its own path component:
     * the shape must match, AND the resolved directory must still live inside the store. The store writes
     * dimension dirs as {@code minecraft_overworld} (see LodSupport.storeRoot), so this pattern is what an
     * honest client always sends -- and a "." or ".." that slips the pattern is still caught by the
     * containment check below (belt and suspenders, matching CsLodHttpServer.resolve).
     */
    private static final Pattern DIM_DIR = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** The radius each client's renderer is actually configured to draw, in blocks. */
    private static final Map<UUID, Integer> RADIUS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Players who asked, can draw, and were told we had nothing -- yet.
     *
     * <p>This set is the whole fix. A player who joins before the operator runs the pregen used to be told
     * "no data" once and then left to rot: the client stood down, and no amount of playing or travelling
     * brought it back. Since a pregen takes hours and players sit on the server THROUGH it, that was the
     * normal case, not an edge one. Now we remember them, and when the store becomes servable we tell them.
     *
     * <p><b>Kept, even though the periodic sync would eventually notice too.</b> The sync only runs once a
     * client is ARMED for a dimension -- it compares an index it already has. A player who joined before
     * there was anything to index has no index, so this is the path that gets them their first one, and it
     * does it in five seconds rather than five minutes. The two mechanisms cover different halves of the
     * same problem and neither subsumes the other.
     */
    private static final java.util.Set<UUID> WAITING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Dimensions each player has already been told about. Nobody is ever notified about the same one twice. */
    private static final Map<UUID, java.util.Set<String>> ANNOUNCED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Players whose hello we have already narrated. The retries and token renewals are not news. */
    private static final java.util.Set<UUID> GREETED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Players with a scan already running.
     *
     * <p>The scan is off the tick thread, so the tick thread can no longer be the thing that rate-limits it
     * -- which means a client that spams index requests could otherwise queue an unbounded pile of work on
     * the scan thread. One outstanding scan per player: a second request while the first is in flight is
     * DROPPED, not queued. Dropping is correct rather than merely convenient -- the answer to the request we
     * are already computing is the answer to the new one too, and an honest client only ever has one in
     * flight (it holds a busy latch). This bounds the queue at "one per online player", forever.
     */
    private static final java.util.Set<UUID> SCANNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * How often the store watch looks at the disk -- and it looks ONLY while somebody is waiting on it.
     *
     * <p>100 ticks is five seconds. The check itself is one directory open per loaded dimension, stopping at
     * the first region file it sees ({@link CsLodStoreScan}), so even mid-pregen it is a readdir and nothing
     * more. On a server whose store was already there at join -- every normal server -- {@link #WAITING} is
     * empty and this costs a single {@code isEmpty()} per tick and never touches the filesystem at all.
     */
    private static final int STORE_WATCH_TICKS = 100;

    private static int sinceStoreWatch;

    private static CsLodHttpServer http;
    private static MinecraftServer server;

    /**
     * The one thread that is allowed to touch the store on behalf of a request.
     *
     * <p>ONE thread, not a pool. A scan is a readdir plus a stat per in-range region -- for a 340-region
     * store with a 4-region radius, ~86 syscalls and no file content at all -- so it is measured in
     * microseconds and there is nothing to parallelise. A single thread also means the store is never
     * scanned concurrently with itself, and it gives the work a natural queue: at most one entry per online
     * player (see {@link #SCANNING}).
     *
     * <p>Daemon, so it can never hold a shutdown open. Shut down in {@link #onServerStopped}.
     */
    private static volatile ExecutorService scanPool;

    private CsLodServerNet() {
    }

    /** Register the channel. Called at mod init, before any server exists. */
    public static void register() {
        CsLodChannel.register();
    }

    /** A token must never outlive the session that earned it. Called from the disconnect hook. */
    public static void onDisconnect(final UUID player) {
        TOKENS.revoke(player);
        CsLodInBandSender.forget(player);
        RADIUS.remove(player);
        WAITING.remove(player);
        ANNOUNCED.remove(player);
        GREETED.remove(player);
        SCANNING.remove(player);
    }

    /**
     * Bind the backchannel once the server is up and its port is known.
     *
     * <p>Binds whenever LOD is enabled -- NOT only when a store already exists. A fresh server pregenerates
     * AFTER startup, so gating the bind on "the store is there" would mean the backchannel never came up
     * until the next restart, and the operator would have no idea why. The store root is created if
     * missing; an empty store simply 404s until data lands, which is exactly right.
     */
    public static void onServerStarted(final MinecraftServer current) {
        server = current;
        if (!LodSupport.lodEnabled(current)) {
            LOGGER.info("Chunksmith: LOD is disabled; not serving LODs");
            return;
        }
        final Path root = LodSupport.storeRootBase(current);
        try {
            Files.createDirectories(root);
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: cannot create the LOD store root " + root + ": " + e);
            return;
        }
        scanPool = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "chunksmith-lod-scan");
            thread.setDaemon(true);
            return thread;
        });
        http = new CsLodHttpServer(root, TOKENS, CsLodServerNet::isOnline);
        // Same interface the game is bound to, game port + 1. Nothing for an operator to configure.
        // A bind failure is a log line, not an error: the client falls back to the in-band channel.
        http.start(current.getLocalIp(), current.getPort());
    }

    public static void onServerStopped() {
        if (http != null) {
            http.stop();
            http = null;
        }
        final ExecutorService current = scanPool;
        if (current != null) {
            // A scan holds no lock the shutdown needs and writes nothing, so there is nothing to wait FOR --
            // but we wait a moment anyway so a scan in flight is not interrupted mid-readdir, which would
            // print an alarming stack trace into a perfectly clean shutdown.
            current.shutdown();
            try {
                if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                    current.shutdownNow();
                }
            } catch (final InterruptedException e) {
                current.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scanPool = null;
        }
        TOKENS.clear();
        WAITING.clear();
        ANNOUNCED.clear();
        GREETED.clear();
        SCANNING.clear();
        sinceStoreWatch = 0;
        server = null;
    }

    /** For the status command. */
    public static String describe() {
        final String inBand = CsLodInBandSender.pending() > 0
                ? " | in-band backlog: " + CsLodInBandSender.pending() + " regions" : "";
        return (http == null ? "LOD serving: in-band only (no backchannel)" : "LOD serving: " + http.describe())
                + inBand;
    }

    /**
     * Issue a backchannel token for an ONLINE player, out of band of the handshake.
     *
     * <p>This is the answer to "why can't my client download?" -- an operator can mint a token and try the
     * endpoint by hand. It is deliberately op-gated and it still binds the token to that player's real
     * address, so it grants nothing the player could not already get by connecting.
     *
     * @return the token, or null when the backchannel is not running
     */
    public static String issueFor(final ServerPlayer player) {
        if (http == null || http.getPort() == 0) {
            return null;
        }
        return TOKENS.issue(player.getUUID(), addressOf(player));
    }

    private static boolean isOnline(final UUID player) {
        final MinecraftServer current = server;
        return current != null && current.getPlayerList().getPlayer(player) != null;
    }

    /** One inbound protocol message. Always called on the server main thread by {@link CsLodChannel}. */
    public static void receive(final ServerPlayer player, final byte[] data) {
        if (data.length == 0) {
            return;
        }
        try (DataInputStream in = CsLodMessages.reader(data)) {
            final byte id = in.readByte();
            switch (id) {
                case CsLodProtocol.C2S_HELLO -> hello(player, CsLodMessages.decodeClientHello(in));
                case CsLodProtocol.C2S_REQUEST_INDEX -> dispatch(player, in.readUTF(), false);
                case CsLodProtocol.C2S_REQUEST_SUMMARY -> dispatch(player, in.readUTF(), true);
                case CsLodProtocol.C2S_REQUEST_REGIONS -> inBand(player, in);
                case CsLodProtocol.C2S_CANCEL -> {
                    CsLodInBandSender.cancel(player);
                    LOGGER.debug("Chunksmith: LOD transfer cancelled by client");
                }
                default -> LOGGER.warn("Chunksmith: unknown LOD message id " + id);
            }
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: malformed LOD message from " + nameOf(player) + ": " + e);
        }
    }

    private static void hello(final ServerPlayer player, final CsLodMessages.ClientHello hello) throws IOException {
        if (hello.protocolVersion() != CsLodProtocol.VERSION) {
            LOGGER.info("Chunksmith: " + nameOf(player) + " speaks LOD protocol v"
                    + hello.protocolVersion() + ", we speak v" + CsLodProtocol.VERSION + " -- not serving."
                    + " Their Chunksmith needs to match this server's.");
            // ANSWER anyway, with our version and nothing else. A mismatched client that hears nothing back
            // cannot tell "this server does not run Chunksmith" from "this server will not talk to me", so
            // it says nothing and the player is left with no terrain and no explanation. One 30-byte reply
            // and the old client's own version check fires and NAMES the problem in their log. It costs us
            // nothing: no token is minted, no store is scanned, no data is served.
            send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                    CsLodProtocol.VERSION, false, 0, "", List.of())));
            return;
        }
        if (!hello.hasVoxy() && !hello.hasDh()) {
            // No renderer, no point. Say so and stop -- do not burn bandwidth on data nobody can draw.
            send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                    CsLodProtocol.VERSION, false, 0, "", List.of())));
            return;
        }

        final List<String> dims = dimensions();
        final boolean available = !dims.isEmpty();
        final int port = http == null ? 0 : http.getPort();

        // The token is issued HERE, over a connection Mojang has already authenticated. That is the whole
        // point: a UUID or a name proves nothing (both are public), but only a genuinely joined player can
        // ever receive this.
        //
        // And ONLY when there is something to serve. "The store DIRECTORY exists" used to be enough, so a
        // server minted a token the instant a pregen created the folder and before it had written a single
        // region -- a credential to download nothing, which is how an operator ends up reading "1 live
        // token, 0 files" and rightly wondering what it means. No data, no token.
        final String token = (available && port != 0)
                ? TOKENS.issue(player.getUUID(), addressOf(player))
                : "";

        send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, available, port, token, dims)));

        RADIUS.put(player.getUUID(),
                Math.min(MAX_RADIUS_BLOCKS, Math.max(16, hello.radiusBlocks())));

        if (available) {
            WAITING.remove(player.getUUID());
            ANNOUNCED.computeIfAbsent(player.getUUID(),
                    ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).addAll(dims);
        } else {
            // Nothing for them yet. REMEMBER them. The store almost certainly fills up later in this very
            // session -- that is what a pregen is -- and when it does, storeWatchTick tells them.
            WAITING.add(player.getUUID());
        }

        // The client now re-asks on a backed-off clock while it waits, and again to renew its token as it
        // travels. Narrate the FIRST hello of a session; the repeats are bookkeeping, not news, and a log
        // line every fifteen seconds per waiting player is exactly the sort of noise that gets a feature
        // turned off.
        final String line = "Chunksmith: LOD hello from " + nameOf(player)
                + " (voxy=" + hello.hasVoxy() + " dh=" + hello.hasDh() + " radius=" + hello.radiusBlocks()
                + ") -> store=" + available + " backchannel=" + (port == 0 ? "none (in-band)" : port);
        if (GREETED.add(player.getUUID())) {
            LOGGER.info(line);
        } else {
            LOGGER.debug(line);
        }
    }

    /**
     * The fallback: the operator has no backchannel port open, so we drip the regions down the game
     * connection instead. Slow on purpose -- gameplay wins, LOD fills the gaps.
     */
    private static void inBand(final ServerPlayer player, final DataInputStream in) throws IOException {
        final String requested = in.readUTF();
        final int count = in.readInt();
        // Bound BEFORE sizing anything: count came off the wire (see MAX_REGIONS_PER_REQUEST). A client
        // with more than this left to fetch simply asks again -- the index tells it what it is missing.
        if (count < 0 || count > MAX_REGIONS_PER_REQUEST) {
            LOGGER.warn("Chunksmith: ignoring an in-band LOD request from {} for {} regions (max {})",
                    nameOf(player), count, MAX_REGIONS_PER_REQUEST);
            return;
        }
        final List<CsLodMessages.RegionEntry> wanted = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            wanted.add(new CsLodMessages.RegionEntry(in.readInt(), in.readInt(), 0L, 0L));
        }
        final Path root = storeBase();
        if (root == null) {
            return;
        }
        // The dimension came off the wire and is about to build filesystem paths -- validate it the same
        // way the backchannel does, so a "../.." cannot walk the in-band sender out of the store.
        if (safeDimensionDir(root, requested) == null) {
            LOGGER.warn("Chunksmith: ignoring an in-band LOD request from {} for a malformed dimension id",
                    nameOf(player));
            return;
        }
        // Same rule as the index (see index()): we serve the dimension the player is IN, whatever they
        // asked for. The in-band sender stamps this dimension on every slice, and the client stores under
        // the dimension it is told -- so an old client that asks for the overworld from inside the Nether
        // gets the Nether's regions, filed under the Nether.
        final String dimension = dimensionOf(player);
        if (dimension.isEmpty()) {
            return;
        }
        if (!dimension.equals(requested)) {
            LOGGER.info("Chunksmith: {} asked in-band for {} while standing in {} -- serving {} instead.",
                    nameOf(player), requested, dimension, dimension);
        }
        CsLodInBandSender.queue(player, root, dimension, wanted);
        LOGGER.info("Chunksmith: in-band LOD fetch for {} -- {} regions of {} (no backchannel; this is the"
                + " slow path)", nameOf(player), wanted.size(), dimension);
    }

    /** Drip-feed the in-band queues, and watch for the store coming to life. Wired to the server tick. */
    public static void tick(final MinecraftServer current) {
        for (final ServerPlayer player : current.getPlayerList().getPlayers()) {
            CsLodInBandSender.tick(player);
        }
        storeWatchTick(current);
    }

    /**
     * Tell the players who joined before the store existed, once it does.
     *
     * <p>The client PULLS -- that is the design, and this does not change it. We do not push data at anybody:
     * we re-send the HELLO, the same message we already answer a hello with, and the client decides for
     * itself whether to ask for an index. It can still refuse; it can still cancel. All we are doing is
     * finishing the answer to a question they already asked, instead of leaving them with a stale "no".
     *
     * <p>Deliberately cheap and deliberately quiet:
     * <ul>
     *   <li>No watcher thread, no {@code WatchService}, no filesystem poll of any kind unless a player is
     *       actually waiting -- so a normally-provisioned server pays one {@code isEmpty()} per tick;</li>
     *   <li>at most one notice per player per dimension per session ({@link #ANNOUNCED}), so a pregen
     *       writing thousands of regions produces exactly one message, not thousands;</li>
     *   <li>the player is dropped from {@link #WAITING} the moment they are told, so the loop empties
     *       itself and the watch goes back to sleep.</li>
     * </ul>
     */
    private static void storeWatchTick(final MinecraftServer current) {
        if (WAITING.isEmpty()) {
            sinceStoreWatch = 0;
            return;
        }
        if (++sinceStoreWatch < STORE_WATCH_TICKS) {
            return;
        }
        sinceStoreWatch = 0;

        final List<String> dims = dimensions();
        if (dims.isEmpty()) {
            return;
        }
        final int port = http == null ? 0 : http.getPort();

        for (final UUID uuid : List.copyOf(WAITING)) {
            final ServerPlayer player = current.getPlayerList().getPlayer(uuid);
            if (player == null) {
                WAITING.remove(uuid);
                continue;
            }
            final java.util.Set<String> told = ANNOUNCED.computeIfAbsent(uuid,
                    ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
            if (!told.addAll(dims)) {
                // They already know about every dimension we can serve. Never say it twice.
                WAITING.remove(uuid);
                continue;
            }
            WAITING.remove(uuid);

            final String token = port != 0 ? TOKENS.issue(uuid, addressOf(player)) : "";
            try {
                send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                        CsLodProtocol.VERSION, true, port, token, dims)));
            } catch (final IOException e) {
                LOGGER.warn("Chunksmith: could not tell {} that the LOD store is ready: {}",
                        nameOf(player), e.toString());
                continue;
            }
            LOGGER.info("Chunksmith: the LOD store now has data for {} -- telling {}, who joined before it"
                    + " existed. No relog needed.", dims, nameOf(player));
        }
    }

    /** Send raw protocol bytes to a player. */
    public static void sendTo(final ServerPlayer player, final byte[] data) {
        send(player, data);
    }

    // ------------------------------------------------------------------ index + summary

    /**
     * Everything the scan thread needs, captured on the MAIN thread.
     *
     * <p>This record is the thread boundary, and every field in it is here because reading it off the main
     * thread would be a data race: a player's position, their level, and the player object itself are all
     * mutated by the tick. So we read them once, synchronously, on the tick -- which is nanoseconds -- and
     * the scan thread then works from an immutable snapshot and never touches a game object again.
     *
     * @param summaryOnly true for a sync poll (fold the answer to two numbers), false for a full index
     */
    private record Request(UUID uuid, String name, String dimension, int px, int pz, int radius,
                           boolean summaryOnly) {
    }

    /**
     * Take the snapshot, and hand the filesystem work to the scan thread.
     *
     * <p>ALWAYS called on the server main thread. It is the last thing on the main thread that this feature
     * does, and everything it does here is O(1).
     */
    private static void dispatch(final ServerPlayer player, final String requested, final boolean summaryOnly)
            throws IOException {
        final Path root = storeBase();
        if (root == null) {
            return;
        }
        // The dimension came off the wire and is used to build a filesystem path -- validate + contain it.
        if (safeDimensionDir(root, requested) == null) {
            LOGGER.warn("Chunksmith: ignoring a LOD request from {} for a malformed dimension id",
                    nameOf(player));
            return;
        }

        // AN INDEX IS ONLY MEANINGFUL FOR THE DIMENSION THE PLAYER IS STANDING IN. It is filtered by the
        // radius of their renderer measured from THEIR position (see inRange) -- and a position is a
        // position in a particular world. Asking for the overworld's index while standing in the Nether
        // returns overworld regions selected by Nether coordinates: nonsense, and worse than nonsense
        // because the client will then draw them.
        //
        // A 3.1.0-beta-2 client did exactly that: it latched onto the first dimension we listed at join and
        // never asked for another. We cannot patch a jar that is already in a player's mods folder -- but we
        // do not have to honour a request we know is wrong. Serve the dimension they are ACTUALLY in, and
        // say which one it is: the client stores and injects under the dimension WE echo back.
        final String dimension = dimensionOf(player);
        if (dimension.isEmpty()) {
            return;
        }
        if (!dimension.equals(requested)) {
            LOGGER.info("Chunksmith: {} asked for the LOD index of {} while standing in {} -- serving {}"
                    + " instead.", nameOf(player), requested, dimension, dimension);
        }

        final UUID uuid = player.getUUID();
        // One scan per player at a time. See SCANNING: this is what keeps the scan queue bounded now that
        // the tick thread is no longer the thing throttling it.
        if (!SCANNING.add(uuid)) {
            LOGGER.debug("Chunksmith: {} already has a LOD scan in flight; dropping the duplicate request",
                    nameOf(player));
            return;
        }

        final Request request = new Request(uuid, nameOf(player), dimension,
                (int) player.getX(), (int) player.getZ(),
                RADIUS.getOrDefault(uuid, CsLodProtocol.DEFAULT_RADIUS_BLOCKS),
                summaryOnly);

        final ExecutorService pool = scanPool;
        if (pool == null) {
            SCANNING.remove(uuid);
            return;
        }
        try {
            pool.execute(() -> run(root, request));
        } catch (final RejectedExecutionException e) {
            // The server is stopping. Nothing to answer, and nothing to complain about.
            SCANNING.remove(uuid);
        }
    }

    /**
     * The scan -- on the scan thread, never on the tick.
     *
     * <p>Readdir the dimension directory, stat the regions that are in range, and either send the whole
     * index or fold it to two numbers. Not one byte of any region file is read.
     */
    private static void run(final Path root, final Request request) {
        try {
            final Path dir = safeDimensionDir(root, request.dimension());
            if (dir == null) {
                return;
            }
            final List<CsLodMessages.RegionEntry> regions = scan(dir, request);

            final byte[] message;
            if (request.summaryOnly()) {
                long aggregate = 0L;
                for (final CsLodMessages.RegionEntry entry : regions) {
                    aggregate = CsLodSummary.fold(aggregate, entry.regionX(), entry.regionZ(), entry.hash());
                }
                message = CsLodMessages.encode(new CsLodMessages.RegionSummary(
                        request.dimension(), regions.size(), aggregate));
                LOGGER.debug("Chunksmith: LOD sync summary for {} -- {} regions of {}",
                        request.name(), regions.size(), request.dimension());
            } else {
                message = CsLodMessages.encode(new CsLodMessages.RegionIndex(request.dimension(), regions));
            }

            // Back to the main thread to SEND. The scan had no business on the tick; the send does -- it is
            // the one part of this that touches a live player object, and hopping back for it costs a queue
            // entry and buys us not having to reason about whether every loader's channel is thread-safe.
            final MinecraftServer current = server;
            if (current == null) {
                return;
            }
            current.execute(() -> {
                final ServerPlayer player = current.getPlayerList().getPlayer(request.uuid());
                if (player != null) {
                    send(player, message);
                }
            });
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: could not scan the LOD store for {}: {}", request.name(), e.toString());
        } finally {
            SCANNING.remove(request.uuid());
        }
    }

    /**
     * The one scan, shared by the index and the summary -- and they MUST share it.
     *
     * <p>If the summary were computed over a different set than the index, the sync would compare two
     * answers to two different questions and disagree forever: every poll would find a "difference", pull a
     * full index, find nothing to fetch, and do it all again on the next interval. The whole value of the
     * sync is that an idle poll is idle, so there is exactly one definition of "the regions this player can
     * see", and this is it.
     *
     * <p>Per region, in this order, cheapest first:
     * <ol>
     *   <li>the NAME must parse as one of ours ({@code r.<x>.<z>.cslod}) -- a string test, no syscall;</li>
     *   <li>it must be {@link #inRange} of the player -- integer arithmetic, no syscall. Doing this BEFORE
     *       the stat is the difference between statting 81 files and statting all 340;</li>
     *   <li>ONE {@code readAttributes} gives us mtime and size together -- one {@code statx}, not two. That
     *       is the only syscall per region, and it is all we need for both the settle check and the
     *       freshness token;</li>
     *   <li>it must be SETTLED -- a region the pregen is still appending to has header slots pointing past
     *       the end of what a client would receive. Ten seconds untouched (see {@link CsLodStoreScan}).</li>
     * </ol>
     *
     * <p>Then sorted NEAREST FIRST and truncated to the caps. Sorting is what makes the truncation
     * deterministic -- {@code Files.list} order is whatever the filesystem says, so an un-sorted cap would
     * return a different subset on each call and the summary would never match the index. It also makes the
     * truncation KIND: the regions a client loses to the cap are the furthest ones, which are the ones it
     * can least see, and it gets them as it walks toward them.
     */
    private static List<CsLodMessages.RegionEntry> scan(final Path dir, final Request request)
            throws IOException {
        final List<CsLodMessages.RegionEntry> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        final long now = System.currentTimeMillis();
        try (var files = Files.list(dir)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(CsLodStoreScan.REGION_SUFFIX)) {
                    continue;
                }
                final String[] parts = name.split("\\.");
                if (parts.length != 4) {
                    continue;
                }
                final int regionX;
                final int regionZ;
                try {
                    regionX = Integer.parseInt(parts[1]);
                    regionZ = Integer.parseInt(parts[2]);
                } catch (final NumberFormatException ignored) {
                    continue;   // not one of ours
                }
                if (!inRange(request, regionX, regionZ)) {
                    continue;
                }
                final BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(file, BasicFileAttributes.class);
                } catch (final IOException e) {
                    continue;   // it went away under us; the client re-asks
                }
                if (!attrs.isRegularFile()) {
                    continue;
                }
                // A file we cannot vouch for is a file we do not serve -- same rule as CsLodStoreScan, and
                // now answered from attributes we already have rather than a second stat.
                if (now - attrs.lastModifiedTime().toMillis() < CsLodStoreScan.SETTLE_MILLIS) {
                    continue;
                }
                found.add(new CsLodMessages.RegionEntry(regionX, regionZ,
                        CsLodRegionHash.of(attrs.lastModifiedTime().toMillis(), attrs.size()),
                        attrs.size()));
            }
        }

        found.sort(Comparator
                .comparingLong((CsLodMessages.RegionEntry e) -> distanceSquared(request, e.regionX(), e.regionZ()))
                .thenComparingInt(CsLodMessages.RegionEntry::regionX)
                .thenComparingInt(CsLodMessages.RegionEntry::regionZ));

        return cap(found, request);
    }

    /**
     * Apply BOTH caps -- the region count and the byte budget -- to a nearest-first list.
     *
     * <p>The byte budget is the one that was missing (see {@link #MAX_INDEX_BYTES}), and it is the one that
     * actually binds: a 4096-region cap on a store of 4.6 MB regions permits ~18 GB.
     */
    private static List<CsLodMessages.RegionEntry> cap(final List<CsLodMessages.RegionEntry> found,
                                                       final Request request) {
        long bytes = 0L;
        for (int i = 0; i < found.size(); i++) {
            final long next = bytes + found.get(i).sizeBytes();
            if (i >= MAX_REGIONS_PER_REQUEST || next > MAX_INDEX_BYTES) {
                LOGGER.warn("Chunksmith: LOD index for {} capped at {} of {} regions ({} MB of a {} MB"
                                + " budget, radius {}). The client re-requests as the player moves, so it"
                                + " gets the rest as it travels -- nearest regions first.",
                        request.name(), i, found.size(), bytes / (1024 * 1024),
                        MAX_INDEX_BYTES / (1024 * 1024), request.radius());
                return List.copyOf(found.subList(0, i));
            }
            bytes = next;
        }
        return found;
    }

    /**
     * Resolve a wire dimension id to a directory INSIDE the store, or null if it is malformed or tries to
     * escape. Two gates, same as {@code CsLodHttpServer.resolve}: the shape must match {@link #DIM_DIR},
     * AND the normalized result must still start with the store root (which catches a "." / ".." that the
     * pattern would otherwise admit).
     */
    private static Path safeDimensionDir(final Path root, final String dimension) {
        if (dimension == null || dimension.isEmpty() || !DIM_DIR.matcher(dimension).matches()) {
            return null;
        }
        final Path dir = root.resolve(dimension).normalize();
        return dir.startsWith(root) ? dir : null;
    }

    /**
     * Is this region within the radius the client's renderer can actually DRAW, measured from the player?
     *
     * <p>The client tells us its configured LOD distance in the handshake, and we follow it -- lower or
     * higher. Sending beyond it is bandwidth spent on terrain the player will never see; sending less leaves
     * visible holes. A store can be hundreds of megabytes, and shipping all of it to someone whose renderer
     * draws 256 blocks would be indefensible.
     *
     * <p>A region is 32 chunks = 512 blocks square, so we test the region's BOX against the radius, not its
     * corner -- a region only partly inside the radius still contains terrain the player can see.
     */
    private static boolean inRange(final Request request, final int regionX, final int regionZ) {
        return distanceSquared(request, regionX, regionZ)
                <= (long) request.radius() * request.radius();
    }

    /** Squared distance from the player to the NEAREST POINT of a region's box. Also the sort key. */
    private static long distanceSquared(final Request request, final int regionX, final int regionZ) {
        final int minX = regionX * 512;
        final int minZ = regionZ * 512;
        final int maxX = minX + 511;
        final int maxZ = minZ + 511;

        final int dx = Math.max(0, Math.max(minX - request.px(), request.px() - maxX));
        final int dz = Math.max(0, Math.max(minZ - request.pz(), request.pz() - maxZ));
        return (long) dx * dx + (long) dz * dz;
    }

    /**
     * The dimensions we can actually SERVE, right now.
     *
     * <p>A dimension DIRECTORY is not LOD data. A pregen creates {@code chunksmith/lod/<dim>/} the instant it
     * starts and does not write a region into it for some time after -- and this method used to advertise it
     * the moment it appeared. So the server told clients it had a dimension it could not serve one byte of,
     * and (see {@link #hello}) minted a backchannel token to go with it. A dimension counts once there is
     * something in it. {@link CsLodStoreScan} answers that, and stops at the first region file it finds.
     */
    private static List<String> dimensions() {
        final MinecraftServer current = server;
        if (current == null) {
            return List.of();
        }
        final List<Path> dirs = new ArrayList<>();
        for (final ServerLevel level : current.getAllLevels()) {
            dirs.add(LodSupport.storeRoot(level));
        }
        return CsLodStoreScan.servable(dirs, System.currentTimeMillis());
    }

    /**
     * The store key of the dimension the player is ACTUALLY in -- the authority for everything we serve them.
     *
     * <p>Resolved by identity against the server's own levels, so it is the same string
     * {@link LodSupport#storeRoot} named that dimension's directory with. A player is always in one of the
     * server's levels, so the empty return is unreachable in practice; it exists so a caller can never get a
     * plausible-looking wrong answer.
     */
    private static String dimensionOf(final ServerPlayer player) {
        final MinecraftServer current = server;
        if (current == null) {
            return "";
        }
        for (final ServerLevel level : current.getAllLevels()) {
            if (level == player.level()) {
                return LodSupport.dimensionKey(level);
            }
        }
        return "";
    }

    private static Path storeBase() {
        final MinecraftServer current = server;
        return current == null ? null : LodSupport.storeRootBase(current);
    }

    /** The player's display name. authlib renamed GameProfile.getName() to name() at MC 1.21.9. */
    private static String nameOf(final ServerPlayer player) {
        //[[[cog
        // import cog, compat
        // cog.outl("return player.getGameProfile().%s();" % compat.profile_name_call(mcver))
        //]]]
        //[[[end]]]
    }

    private static String addressOf(final ServerPlayer player) {
        final var address = player.connection.getRemoteAddress();
        if (address instanceof final InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return "";
    }

    private static void send(final ServerPlayer player, final byte[] data) {
        CsLodChannel.send(player, data);
    }
}
