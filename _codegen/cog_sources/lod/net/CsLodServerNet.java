package com.kishku7.chunksmith.lod.net;

import com.kishku7.chunksmith.ChunksmithProvider;
import com.kishku7.chunksmith.lod.net.CsLodControl;
import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the Chunksmith LOD protocol.
 *
 * <p>The client pulls: it says hello (telling us which renderers it has), asks for a region index,
 * works out what it is missing, and fetches it (over the HTTP backchannel when that is available,
 * in-band when it is not). It can stop at any time; the server never pushes uninvited, and refuses a
 * client with no renderer rather than burn bandwidth on terrain nobody can draw. Loader-blind: every
 * wire call goes through {@link CsLodChannel}, the one per-loader/per-era seam (Fabric raw channel
 * &lt;1.20.2, Fabric payload registry, NeoForge PayloadRegistrar, Forge SimpleChannel), while
 * CsLodProtocol / CsLodMessages / CsLodTokens / CsLodHttpServer live in shared_common and never see
 * a Minecraft type.
 *
 * <h2>Nothing in here reads a region file, and nothing in here touches a disk on the tick thread</h2>
 *
 * <p>Both were false in 3.1.0-beta-3, and together they took a live production server to 100% RAM
 * and hung its shutdown for 67 minutes. {@code index()} ran on the server main thread and called a
 * {@code hash()} that did {@code crc.update(Files.readAllBytes(file))} on every region file inside
 * the client's radius: on a 340-region / 1567 MB store, 366.9 MB read and allocated per index
 * request, every byte[] a G1-humongous allocation straight into old gen, and the client re-asks
 * every five seconds while the player travels. ~73 MB/s of humongous garbage on the tick thread,
 * competing with a pregen for the same disk, until {@code saveAllChunks} could not allocate. Three
 * changes, load-bearing together: the freshness token is derived from (mtime, size) rather than the
 * bytes ({@link CsLodRegionHash}), one {@code statx} per region, no reads; the scan runs off the
 * main thread, which now takes only an immutable snapshot of who is asking, where they stand and
 * what they can draw ({@link Request}); and the answer is bounded in bytes, because {@link
 * CsLodIndexScan#MAX_REGIONS} alone is no bound: 4096 x 7 MB is ~28 GB.
 *
 * <p>Shared source; the canonical location is _codegen/cog_sources/lod. Edit only there: the
 * per-cell copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodServerNet {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static final CsLodTokens TOKENS = new CsLodTokens();

    /** ~16k blocks: further than any LOD renderer draws, and it bounds the index we build. */
    private static final int MAX_RADIUS_BLOCKS = 16384;

    /**
     * A wire dimension id is one store subdirectory name and nothing else. Validated exactly as the
     * HTTP backchannel validates its own path component: the shape must match, and the resolved
     * directory must still live inside the store, so a "." or ".." that slips the pattern is still
     * caught by the containment check below (belt and suspenders, matching CsLodHttpServer.resolve).
     */
    private static final Pattern DIM_DIR = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** The radius each client's renderer is actually configured to draw, in blocks. */
    private static final Map<UUID, Integer> RADIUS = new ConcurrentHashMap<>();

    /**
     * Players who asked, can draw, and were told we had nothing -- yet. A player who joins before
     * the operator runs the pregen used to be told "no data" once and then left to rot for the rest
     * of the session, and since a pregen takes hours with players sitting through it, that was the
     * ordinary case. Kept even though the periodic sync would eventually notice too: the sync only
     * runs once a client is armed for a dimension, and a player who joined before there was anything
     * to index has no index. This is the path that gets them their first one, in five seconds rather
     * than five minutes.
     */
    private static final Set<UUID> WAITING = ConcurrentHashMap.newKeySet();

    /** Dimensions each player has already been told about. Nobody is ever notified about the same one twice. */
    private static final Map<UUID, Set<String>> ANNOUNCED =
            new ConcurrentHashMap<>();

    /** Players whose hello we have already narrated. The retries and token renewals are not news. */
    private static final Set<UUID> GREETED = ConcurrentHashMap.newKeySet();

    /**
     * Players with a scan already running. The tick thread no longer rate-limits the scan, so a
     * client that spams index requests could otherwise queue an unbounded pile of work on the scan
     * thread. One outstanding scan per player: a second request while the first is in flight is
     * dropped rather than queued; the answer being computed is the answer to the new one too, and an
     * honest client only ever has one in flight (it holds a busy latch). That bounds the queue at
     * one entry per online player.
     */
    private static final Set<UUID> SCANNING = ConcurrentHashMap.newKeySet();

    /**
     * How often the store watch looks at the disk, and it looks only while somebody is waiting on
     * it. 100 ticks is five seconds; the check is one directory open per loaded dimension, stopping
     * at the first region file it sees ({@link CsLodStoreScan}). On a server whose store was already
     * there at join {@link #WAITING} is empty, so this costs one {@code isEmpty()} per tick and no
     * filesystem call at all.
     */
    private static final int STORE_WATCH_TICKS = 100;

    private static int sinceStoreWatch;

    private static CsLodHttpServer http;
    private static MinecraftServer server;

    /**
     * The one thread that is allowed to touch the store on behalf of a request. one thread, not a
     * pool: a scan is a readdir plus a stat per in-range region (~86 syscalls and no file content at
     * all for a 340-region store at a 4-region radius), so it is microseconds and there is nothing
     * to parallelise. A single thread also means the store is never scanned concurrently with
     * itself, and gives the work a natural queue of at most one entry per online player ({@link
     * #SCANNING}). Daemon, so it can never hold a shutdown open; shut down in {@link
     * #onServerStopped}.
     */
    private static volatile ExecutorService scanPool;

    private CsLodServerNet() {
    }

    public static void register() {
        CsLodChannel.register();
    }

    /** A token must never outlive the session that earned it. Called from the disconnect hook. */
    public static void onDisconnect(UUID player) {
        TOKENS.revoke(player);
        CsLodInBandSender.forget(player);
        RADIUS.remove(player);
        WAITING.remove(player);
        ANNOUNCED.remove(player);
        GREETED.remove(player);
        SCANNING.remove(player);
    }

    /**
     * Binds the backchannel once the server is up and its port is known. The bind happens whenever
     * LOD is enabled, not only when a store already exists. A fresh server pregenerates after
     * startup, so gating the bind on "the store is there" would mean the backchannel never came up
     * until the next restart, with nothing to tell the operator why. An empty store simply 404s
     * until data lands.
     */
    public static void onServerStarted(MinecraftServer current) {
        server = current;
        if (!LodSupport.lodEnabled(current)) {
            LOGGER.info("Chunksmith: LOD is disabled; not serving LODs");
            return;
        }
        Path root = LodSupport.storeRootBase(current);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: cannot create the LOD store root " + root + ": " + e);
            return;
        }
        scanPool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chunksmith-lod-scan");
            thread.setDaemon(true);
            return thread;
        });
        http = new CsLodHttpServer(root, TOKENS, CsLodServerNet::isOnline);
        // Same interface the game is bound to; the port is gamePort + 1 unless the operator named one
        // (lodBackchannelPort); mod_support #19. A bind failure is not fatal: the client falls back in-band.
        http.start(current.getLocalIp(), current.getPort(), configuredPort());
        // From here, `/cs set lodBackchannelPort` can move the listener without a restart.
        CsLodControl.register(
                CsLodServerNet::rebind,
                current::getPort,
                () -> http == null ? "backchannel: not running (in-band fallback)" : http.describe());
    }

    /**
     * Returns the operator's chosen backchannel port, or 0 to derive it. 0 whenever the mod is not loaded.
     *
     * @return the configured port, or 0 to derive one
     */
    private static int configuredPort() {
        // ChunksmithProvider.get() throws when unloaded, so gate on isLoaded() first.
        return ChunksmithProvider.isLoaded()
                ? ChunksmithProvider.get().getConfig().getLodBackchannelPort()
                : 0;
    }

    /**
     * Moves the backchannel to the currently configured port without a restart, after {@code /cs set
     * lodBackchannelPort}. Three things must happen together or the change is worse than useless.
     * The old listener stops (or the old port stays open and nothing has moved), the new one binds,
     * and every connected client is told and re-issued a token; {@link CsLodHttpServer#stop()}
     * clears the token table, so a client that is not re-greeted holds a credential the new listener
     * will not honour and quietly 404s until it relogs. Main thread only, because it sends packets.
     *
     * @return the port now bound, or 0 if the backchannel is not running (in-band fallback)
     */
    public static int rebind() {
        MinecraftServer current = server;
        if (current == null) {
            return 0;
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        if (!LodSupport.lodEnabled(current)) {
            return 0;
        }
        Path root = LodSupport.storeRootBase(current);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: cannot create the LOD store root " + root + ": " + e);
            return 0;
        }
        http = new CsLodHttpServer(root, TOKENS, CsLodServerNet::isOnline);
        int bound = http.start(current.getLocalIp(), current.getPort(), configuredPort());
        readvertise(current, bound);
        return bound;
    }

    /**
     * Re-send the hello to every client that has spoken the protocol, carrying the new port and a
     * fresh token. Only GREETED players: a vanilla client would log an unknown id and drop it. A
     * player whose send fails is left alone rather than retried; they re-hello on their next join,
     * and the in-band channel keeps working meanwhile.
     */
    private static void readvertise(MinecraftServer current, int port) {
        List<String> dims = dimensions();
        boolean available = !dims.isEmpty();
        int told = 0;
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            if (!GREETED.contains(player.getUUID())) {
                continue;
            }
            String token = (available && port != 0)
                    ? TOKENS.issue(player.getUUID(), addressOf(player))
                    : "";
            try {
                send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                        CsLodProtocol.VERSION, available, port, token, dims)));
                told++;
            } catch (IOException e) {
                LOGGER.warn("Chunksmith: could not tell {} about the new backchannel port: {}",
                        nameOf(player), e.toString());
            }
        }
        LOGGER.info("Chunksmith: LOD backchannel moved to port {}; {} connected client(s) re-issued"
                + " a token. No relog needed.", port == 0 ? "none (in-band)" : String.valueOf(port), told);
    }

    public static void onServerStopped() {
        // First: a rebind that fired after this point would resurrect a listener for a dying server.
        CsLodControl.clear();
        if (http != null) {
            http.stop();
            http = null;
        }
        ExecutorService current = scanPool;
        if (current != null) {
            // A scan holds no lock the shutdown needs and writes nothing, but we wait a moment anyway so a
            // scan in flight is not interrupted mid-readdir into an otherwise clean shutdown.
            current.shutdown();
            try {
                if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
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

    public static String describe() {
        String inBand = CsLodInBandSender.pending() > 0
                ? " | in-band backlog: " + CsLodInBandSender.pending() + " regions" : "";
        return (http == null ? "LOD serving: in-band only (no backchannel)" : "LOD serving: " + http.describe())
                + inBand;
    }

    /**
     * Issues a backchannel token for an online player, out of band of the handshake, so an operator
     * can mint a token and try the endpoint by hand. Op-gated, and still bound to that player's real
     * address, so it grants nothing the player could not already get by connecting.
     *
     * @return the token, or null when the backchannel is not running
     */
    public static String issueFor(ServerPlayer player) {
        if (http == null || http.getPort() == 0) {
            return null;
        }
        return TOKENS.issue(player.getUUID(), addressOf(player));
    }

    private static boolean isOnline(UUID player) {
        MinecraftServer current = server;
        return current != null && current.getPlayerList().getPlayer(player) != null;
    }

    /**
     * Handles one inbound protocol message. Always called on the server main thread by {@link CsLodChannel}.
     */
    public static void receive(ServerPlayer player, byte[] data) {
        if (data.length == 0) {
            return;
        }
        try (DataInputStream in = CsLodMessages.reader(data)) {
            byte id = in.readByte();
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
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: malformed LOD message from " + nameOf(player) + ": " + e);
        }
    }

    private static void hello(ServerPlayer player, CsLodMessages.ClientHello hello) throws IOException {
        if (hello.protocolVersion() != CsLodProtocol.VERSION) {
            LOGGER.info("Chunksmith: " + nameOf(player) + " speaks LOD protocol v"
                    + hello.protocolVersion() + ", we speak v" + CsLodProtocol.VERSION + ", so we are not serving."
                    + " Their Chunksmith needs to match this server's.");
            // Answer anyway, with our version and nothing else: a mismatched client that hears nothing
            // back cannot tell "no Chunksmith here" from "will not talk to me". One 30-byte reply and the
            // old client's own version check names the problem in their log. No token, no scan, no data.
            send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                    CsLodProtocol.VERSION, false, 0, "", List.of())));
            return;
        }
        if (!hello.hasVoxy() && !hello.hasDh()) {
            // No renderer, no DATA. Answer with an empty hello and stop before the store is even looked at:
            // no token minted, no dimension list built, no radius recorded, and deliberately not added to
            // WAITING, so storeWatchTick never wakes them with an offer they cannot accept.
            send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                    CsLodProtocol.VERSION, false, 0, "", List.of())));
            // But do record the greeting (3.4.0): GREETED is what hasLodClient() answers, which decides
            // whether /cslod set relays to them. Leaving it out was why a player with Chunksmith and no
            // renderer could not reach their own client settings at all. Guarded by add(): one line/session.
            if (GREETED.add(player.getUUID())) {
                LOGGER.info("Chunksmith: LOD hello from " + nameOf(player)
                        + " (voxy=false dh=false, no LOD renderer) -> serving no data;"
                        + " /cslod set can reach them");
            }
            return;
        }

        List<String> dims = dimensions();
        boolean available = !dims.isEmpty();
        int port = http == null ? 0 : http.getPort();

        // The token is issued on this connection, which Mojang has already authenticated: a UUID or a name
        // proves nothing (both are public), but only a genuinely joined player can receive this, and only
        // when there is something to serve. "The store directory exists" used to be enough, so a server
        // minted a token the instant a pregen created the folder and before it wrote a single region: a
        // credential to download nothing, and an operator reading "1 live token, 0 files".
        String token = (available && port != 0)
                ? TOKENS.issue(player.getUUID(), addressOf(player))
                : "";

        send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, available, port, token, dims)));

        RADIUS.put(player.getUUID(),
                Math.min(MAX_RADIUS_BLOCKS, Math.max(16, hello.radiusBlocks())));

        if (available) {
            WAITING.remove(player.getUUID());
            ANNOUNCED.computeIfAbsent(player.getUUID(),
                    ignored -> ConcurrentHashMap.newKeySet()).addAll(dims);
        } else {
            // Nothing for them yet. Remember them: the store usually fills up later in this very session.
            WAITING.add(player.getUUID());
        }

        // The client re-asks on a backed-off clock and again to renew its token. Narrate only the first
        // hello of a session: a line every fifteen seconds per waiting player is how a feature gets disabled.
        String line = "Chunksmith: LOD hello from " + nameOf(player)
                + " (voxy=" + hello.hasVoxy() + " dh=" + hello.hasDh() + " radius=" + hello.radiusBlocks()
                + ") -> store=" + available + " backchannel=" + (port == 0 ? "none (in-band)" : port);
        if (GREETED.add(player.getUUID())) {
            LOGGER.info(line);
        } else {
            LOGGER.debug(line);
        }
    }

    /**
     * The fallback for when no backchannel port is open: drip the regions down the game connection
     * instead, at a rate that leaves room for gameplay traffic.
     */
    private static void inBand(ServerPlayer player, DataInputStream in) throws IOException {
        String requested = in.readUTF();
        int count = in.readInt();
        // Bound the count before sizing anything: it came off the wire (see CsLodIndexScan.MAX_REGIONS).
        if (count < 0 || count > CsLodIndexScan.MAX_REGIONS) {
            LOGGER.warn("Chunksmith: ignoring an in-band LOD request from {} for {} regions (max {})",
                    nameOf(player), count, CsLodIndexScan.MAX_REGIONS);
            return;
        }
        List<CsLodMessages.RegionEntry> wanted = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            wanted.add(new CsLodMessages.RegionEntry(in.readInt(), in.readInt(), 0L, 0L));
        }
        Path root = storeBase();
        if (root == null) {
            return;
        }
        // The dimension came off the wire and is about to build filesystem paths; validate + contain it.
        if (safeDimensionDir(root, requested) == null) {
            LOGGER.warn("Chunksmith: ignoring an in-band LOD request from {} for a malformed dimension id",
                    nameOf(player));
            return;
        }
        // Same rule as the index (see dispatch()): serve the dimension the player is in, whatever they
        // asked for. The sender stamps it on every slice and the client files under the dimension it is told.
        String dimension = dimensionOf(player);
        if (dimension.isEmpty()) {
            return;
        }
        if (!dimension.equals(requested)) {
            LOGGER.info("Chunksmith: {} asked in-band for {} while standing in {}; serving {} instead.",
                    nameOf(player), requested, dimension, dimension);
        }
        CsLodInBandSender.queue(player, root, dimension, wanted);
        LOGGER.info("Chunksmith: in-band LOD fetch for {}. {} regions of {} (no backchannel; this is the"
                + " slow path)", nameOf(player), wanted.size(), dimension);
    }

    /** Drip-feeds the in-band queues, and watches for the store coming to life. Wired to the server tick. */
    public static void tick(MinecraftServer current) {
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            CsLodInBandSender.tick(player);
        }
        storeWatchTick(current);
    }

    /**
     * Tell the players who joined before the store existed, once it does. The client still pulls: we
     * re-send the hello, the same message we answer a hello with, and the client decides for itself
     * whether to ask for an index. Deliberately cheap and quiet, with no watcher thread, no {@code
     * WatchService}, no filesystem poll at all unless a player is actually waiting (a normal server
     * pays one {@code isEmpty()} per tick); at most one notice per player per dimension per session
     * ({@link #ANNOUNCED}), so a pregen writing thousands of regions produces one message; and the
     * player leaves {@link #WAITING} the moment they are told, so the watch goes back to sleep.
     */
    private static void storeWatchTick(MinecraftServer current) {
        if (WAITING.isEmpty()) {
            sinceStoreWatch = 0;
            return;
        }
        if (++sinceStoreWatch < STORE_WATCH_TICKS) {
            return;
        }
        sinceStoreWatch = 0;

        List<String> dims = dimensions();
        if (dims.isEmpty()) {
            return;
        }
        int port = http == null ? 0 : http.getPort();

        for (UUID uuid : List.copyOf(WAITING)) {
            ServerPlayer player = current.getPlayerList().getPlayer(uuid);
            if (player == null) {
                WAITING.remove(uuid);
                continue;
            }
            Set<String> told = ANNOUNCED.computeIfAbsent(uuid,
                    ignored -> ConcurrentHashMap.newKeySet());
            if (!told.addAll(dims)) {
                // They already know about every dimension we can serve. Never say it twice.
                WAITING.remove(uuid);
                continue;
            }
            WAITING.remove(uuid);

            String token = port != 0 ? TOKENS.issue(uuid, addressOf(player)) : "";
            try {
                send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                        CsLodProtocol.VERSION, true, port, token, dims)));
            } catch (IOException e) {
                LOGGER.warn("Chunksmith: could not tell {} that the LOD store is ready: {}",
                        nameOf(player), e.toString());
                continue;
            }
            LOGGER.info("Chunksmith: the LOD store now has data for {}. Telling {}, who joined before it"
                    + " existed. No relog needed.", dims, nameOf(player));
        }
    }

    public static void sendTo(ServerPlayer player, byte[] data) {
        send(player, data);
    }

    /**
     * Checks whether this player's client has actually spoken the LOD protocol to us. The hello is
     * the only signal that there is a Chunksmith on the other end, and it matters for {@code /cslod
     * set}, because an unknown message id is logged and dropped at the far end silently, so without
     * this check a player on a vanilla client would type a command and have no way to tell "it
     * worked" from "nothing is listening". A renderer is not required to be greeted (3.4.0); the
     * question is whether a Chunksmith is listening, not whether there is anything to draw with.
     *
     * @return true once we have heard a hello from this client
     */
    public static boolean hasLodClient(ServerPlayer player) {
        return GREETED.contains(player.getUUID());
    }

    /**
     * Asks a player's client to list, show or set one of its own LOD settings. Main thread only,
     * like every other send. The client prints the reply into its own chat; this side reports
     * nothing about the outcome because it cannot know it. The file being written is on the player's
     * machine.
     *
     * @return false if the message could not even be built, which is a bug rather than a user error
     */
    public static boolean sendClientSetting(final ServerPlayer player,
                                            final byte action,
                                            final String name,
                                            final String value) {
        try {
            send(player, CsLodMessages.encode(new CsLodMessages.ClientSetting(action, name, value)));
            return true;
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: could not encode a client-setting message: {}", e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ index + summary

    /**
     * Everything the scan thread needs, captured on the main thread. This record is the thread
     * boundary: a player's position, their level and the player object itself are all mutated by the
     * tick, so we read them once synchronously on the tick and the scan thread then works from an
     * immutable snapshot and never touches a game object again.
     *
     * @param summaryOnly true for a sync poll (fold the answer to two numbers), false for a full index
     */
    private record Request(UUID uuid, String name, String dimension, int px, int pz, int radius,
                           boolean summaryOnly) {
    }

    /**
     * Takes the snapshot, and hands the filesystem work to the scan thread. Always called on the
     * server main thread; it is the last thing on the main thread this feature does, and everything
     * here is O(1).
     */
    private static void dispatch(ServerPlayer player, String requested, boolean summaryOnly)
            throws IOException {
        Path root = storeBase();
        if (root == null) {
            return;
        }
        // The dimension came off the wire and is used to build a filesystem path; validate + contain it.
        if (safeDimensionDir(root, requested) == null) {
            LOGGER.warn("Chunksmith: ignoring a LOD request from {} for a malformed dimension id",
                    nameOf(player));
            return;
        }

        // An index is only meaningful for the dimension the player is standing in: it is filtered by the
        // renderer's radius measured from their position, and a position is a position in a particular
        // world. A 3.1.0-beta-2 client latched onto the first dimension we listed at join and never asked
        // for another; we cannot patch a jar already in a player's mods folder, but we do not have to
        // honour a request we know is wrong. Serve the dimension they are actually in, and echo which.
        String dimension = dimensionOf(player);
        if (dimension.isEmpty()) {
            return;
        }
        if (!dimension.equals(requested)) {
            LOGGER.info("Chunksmith: {} asked for the LOD index of {} while standing in {}, serving {}"
                    + " instead.", nameOf(player), requested, dimension, dimension);
        }

        UUID uuid = player.getUUID();
        // One scan per player at a time (see SCANNING): what keeps the scan queue bounded.
        if (!SCANNING.add(uuid)) {
            LOGGER.debug("Chunksmith: {} already has a LOD scan in flight; dropping the duplicate request",
                    nameOf(player));
            return;
        }

        Request request = new Request(uuid, nameOf(player), dimension,
                (int) player.getX(), (int) player.getZ(),
                RADIUS.getOrDefault(uuid, CsLodProtocol.DEFAULT_RADIUS_BLOCKS),
                summaryOnly);

        ExecutorService pool = scanPool;
        if (pool == null) {
            SCANNING.remove(uuid);
            return;
        }
        try {
            pool.execute(() -> run(root, request));
        } catch (RejectedExecutionException e) {
            // The server is stopping. Nothing to answer, and nothing to complain about.
            SCANNING.remove(uuid);
        }
    }

    /**
     * Reads the dimension directory, stats the regions that are in range, and either sends the whole
     * index or folds it to two numbers. The scan runs on the scan thread, never on the tick. Not one
     * byte of any region file is read.
     */
    private static void run(Path root, Request request) {
        try {
            Path dir = safeDimensionDir(root, request.dimension());
            if (dir == null) {
                return;
            }
            CsLodIndexScan.Result scanned = CsLodIndexScan.scan(dir,
                    new CsLodIndexScan.Request(request.dimension(), request.px(), request.pz(),
                            request.radius()), System.currentTimeMillis());
            if (scanned.capped()) {
                LOGGER.warn("Chunksmith: LOD index for {} capped at {} of {} regions ({} MB of a {} MB"
                                + " budget, radius {}). The client re-requests as the player moves, so it"
                                + " gets the rest as it travels (nearest regions first).",
                        request.name(), scanned.regions().size(), scanned.found(),
                        scanned.bytes() / (1024 * 1024), CsLodIndexScan.MAX_BYTES / (1024 * 1024),
                        request.radius());
            }
            List<CsLodMessages.RegionEntry> regions = scanned.regions();

            byte[] message;
            if (request.summaryOnly()) {
                message = CsLodMessages.encode(new CsLodMessages.RegionSummary(
                        request.dimension(), regions.size(), CsLodIndexScan.aggregate(regions)));
                LOGGER.debug("Chunksmith: LOD sync summary for {}, {} regions of {}",
                        request.name(), regions.size(), request.dimension());
            } else {
                message = CsLodMessages.encode(new CsLodMessages.RegionIndex(request.dimension(), regions));
            }

            // Back to the main thread to send: the send is the one part of this that touches a live player
            // object, and hopping back costs a queue entry and buys not reasoning about channel thread-safety.
            MinecraftServer current = server;
            if (current == null) {
                return;
            }
            current.execute(() -> {
                ServerPlayer player = current.getPlayerList().getPlayer(request.uuid());
                if (player != null) {
                    send(player, message);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: could not scan the LOD store for {}: {}", request.name(), e.toString());
        } finally {
            SCANNING.remove(request.uuid());
        }
    }

    /**
     * Resolves a wire dimension id to a directory inside the store, or null if it is malformed or
     * tries to escape. Two gates, same as {@code CsLodHttpServer.resolve}. The shape must match
     * {@link #DIM_DIR}, and the normalized result must still start with the store root (catching a
     * "." / ".." the pattern would otherwise admit).
     *
     * @return the dimension directory, or null when the id is malformed or escapes the store
     */
    private static Path safeDimensionDir(Path root, String dimension) {
        if (dimension == null || dimension.isEmpty() || !DIM_DIR.matcher(dimension).matches()) {
            return null;
        }
        Path dir = root.resolve(dimension).normalize();
        return dir.startsWith(root) ? dir : null;
    }

    /**
     * Returns the dimensions we can actually serve, right now. A dimension directory is not LOD
     * data. A pregen creates {@code chunksmith/lod/<dim>/} the instant it starts and writes no
     * region into it for some time after, and advertising it then told clients we had a dimension we
     * could not serve one byte of, and minted a backchannel token for it (see {@link #hello}).
     * {@link CsLodStoreScan} stops at the first region file it finds.
     *
     * @return the dimension ids that hold at least one region file
     */
    private static List<String> dimensions() {
        MinecraftServer current = server;
        if (current == null) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        for (ServerLevel level : current.getAllLevels()) {
            dirs.add(LodSupport.storeRoot(level));
        }
        return CsLodStoreScan.servable(dirs, System.currentTimeMillis());
    }

    /**
     * Returns the store key of the dimension the player is actually in. It is the authority for
     * everything we serve them. Resolved by identity against the server's own levels, so it is the
     * same string {@link LodSupport#storeRoot} named that dimension's directory with. The empty
     * return is unreachable in practice; it exists so a caller can never get a plausible-looking
     * wrong answer.
     *
     * @return the store key of the player's current dimension
     */
    private static String dimensionOf(ServerPlayer player) {
        MinecraftServer current = server;
        if (current == null) {
            return "";
        }
        for (ServerLevel level : current.getAllLevels()) {
            if (level == player.level()) {
                return LodSupport.dimensionKey(level);
            }
        }
        return "";
    }

    private static Path storeBase() {
        MinecraftServer current = server;
        return current == null ? null : LodSupport.storeRootBase(current);
    }

    /** The player's display name. authlib renamed GameProfile.getName() to name() at MC 1.21.9. */
    private static String nameOf(ServerPlayer player) {
        //[[[cog
        // import cog, compat
        // cog.outl("return player.getGameProfile().%s();" % compat.profile_name_call(mcver))
        //]]]
        //[[[end]]]
    }

    private static String addressOf(ServerPlayer player) {
        var address = player.connection.getRemoteAddress();
        if (address instanceof final InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return "";
    }

    private static void send(ServerPlayer player, byte[] data) {
        CsLodChannel.send(player, data);
    }
}
