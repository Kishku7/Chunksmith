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
 * <p>The client PULLS: it says hello (telling us which renderers it has), asks for a region index, works
 * out what it is missing, and fetches it -- over the HTTP backchannel when that is available, in-band when
 * it is not. It can stop at any time; the server never pushes uninvited, and refuses a client with no
 * renderer rather than burn bandwidth on terrain nobody can draw. Loader-blind: every wire call goes
 * through {@link CsLodChannel}, the one per-loader/per-era seam (Fabric raw channel &lt;1.20.2, Fabric
 * payload registry, NeoForge PayloadRegistrar, Forge SimpleChannel), while CsLodProtocol / CsLodMessages /
 * CsLodTokens / CsLodHttpServer live in shared_common and never see a Minecraft type.
 *
 * <h2>Nothing in here reads a region file, and nothing in here touches a disk on the tick thread</h2>
 *
 * <p>Both were false in 3.1.0-beta-3, and together they took a live production server to 100% RAM and hung
 * its shutdown for 67 minutes. {@code index()} ran on the server main thread and called a {@code hash()}
 * that did {@code crc.update(Files.readAllBytes(file))} on EVERY region file inside the client's radius:
 * on a 340-region / 1567 MB store, 366.9 MB read and allocated per index request, every byte[] a
 * G1-humongous allocation straight into old gen -- and the client re-asks every five seconds while the
 * player travels. ~73 MB/s of humongous garbage on the tick thread, competing with a pregen for the same
 * disk, until {@code saveAllChunks} could not allocate. Three changes, load-bearing together: the
 * freshness token is derived from (mtime, size) rather than the bytes ({@link CsLodRegionHash}) -- one
 * {@code statx} per region, no reads; the scan runs off the main thread, which now takes only an immutable
 * snapshot of who is asking, where they stand and what they can draw ({@link Request}); and the answer is
 * bounded in BYTES ({@link CsLodIndexScan#MAX_REGIONS} alone is no bound -- 4096 x 7 MB is ~28 GB).
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodServerNet {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static final CsLodTokens TOKENS = new CsLodTokens();

    /** ~16k blocks: further than any LOD renderer draws, and it bounds the index we build. */
    private static final int MAX_RADIUS_BLOCKS = 16384;

    /**
     * A wire dimension id is one store SUBDIRECTORY name and nothing else. Validated exactly as the HTTP
     * backchannel validates its own path component: the shape must match AND the resolved directory must
     * still live inside the store, so a "." or ".." that slips the pattern is still caught by the
     * containment check below (belt and suspenders, matching CsLodHttpServer.resolve).
     */
    private static final Pattern DIM_DIR = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** The radius each client's renderer is actually configured to draw, in blocks. */
    private static final Map<UUID, Integer> RADIUS = new ConcurrentHashMap<>();

    /**
     * Players who asked, can draw, and were told we had nothing -- yet. A player who joins before the
     * operator runs the pregen used to be told "no data" once and then left to rot for the rest of the
     * session -- and since a pregen takes hours with players sitting through it, that was the normal case.
     * Kept even though the periodic sync would eventually notice too: the sync only runs once a client is
     * ARMED for a dimension, and a player who joined before there was anything to index has no index. This
     * is the path that gets them their first one, in five seconds rather than five minutes.
     */
    private static final Set<UUID> WAITING = ConcurrentHashMap.newKeySet();

    /** Dimensions each player has already been told about. Nobody is ever notified about the same one twice. */
    private static final Map<UUID, Set<String>> ANNOUNCED =
            new ConcurrentHashMap<>();

    /** Players whose hello we have already narrated. The retries and token renewals are not news. */
    private static final Set<UUID> GREETED = ConcurrentHashMap.newKeySet();

    /**
     * Players with a scan already running. The tick thread no longer rate-limits the scan, so a client that
     * spams index requests could otherwise queue an unbounded pile of work on the scan thread. One
     * outstanding scan per player: a second request while the first is in flight is DROPPED, not queued --
     * the answer being computed is the answer to the new one too, and an honest client only ever has one in
     * flight (it holds a busy latch). Bounds the queue at "one per online player", forever.
     */
    private static final Set<UUID> SCANNING = ConcurrentHashMap.newKeySet();

    /**
     * How often the store watch looks at the disk -- and it looks ONLY while somebody is waiting on it.
     * 100 ticks is five seconds; the check is one directory open per loaded dimension, stopping at the
     * first region file it sees ({@link CsLodStoreScan}). On a server whose store was already there at join
     * {@link #WAITING} is empty, so this costs one {@code isEmpty()} per tick and no filesystem call at all.
     */
    private static final int STORE_WATCH_TICKS = 100;

    private static int sinceStoreWatch;

    private static CsLodHttpServer http;
    private static MinecraftServer server;

    /**
     * The one thread that is allowed to touch the store on behalf of a request. ONE thread, not a pool: a
     * scan is a readdir plus a stat per in-range region -- ~86 syscalls and no file content at all for a
     * 340-region store at a 4-region radius -- so it is microseconds and there is nothing to parallelise.
     * A single thread also means the store is never scanned concurrently with itself, and gives the work a
     * natural queue of at most one entry per online player ({@link #SCANNING}). Daemon, so it can never
     * hold a shutdown open; shut down in {@link #onServerStopped}.
     */
    private static volatile ExecutorService scanPool;

    private CsLodServerNet() {
    }

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
     * Bind the backchannel once the server is up and its port is known. Binds whenever LOD is enabled --
     * NOT only when a store already exists: a fresh server pregenerates AFTER startup, so gating the bind
     * on "the store is there" would mean the backchannel never came up until the next restart and the
     * operator would have no idea why. An empty store simply 404s until data lands.
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
        // Same interface the game is bound to; the PORT is gamePort + 1 unless the operator named one
        // (lodBackchannelPort) -- mod_support #19. A bind failure is not fatal: the client falls back in-band.
        http.start(current.getLocalIp(), current.getPort(), configuredPort());
        // From here, `/cs set lodBackchannelPort` can move the listener without a restart.
        CsLodControl.register(
                CsLodServerNet::rebind,
                current::getPort,
                () -> http == null ? "backchannel: not running (in-band fallback)" : http.describe());
    }

    /** The operator's chosen backchannel port, or 0 to derive it. 0 whenever the mod is not loaded. */
    private static int configuredPort() {
        // ChunksmithProvider.get() THROWS when unloaded, so gate on isLoaded() first.
        return ChunksmithProvider.isLoaded()
                ? ChunksmithProvider.get().getConfig().getLodBackchannelPort()
                : 0;
    }

    /**
     * Move the backchannel to the currently configured port WITHOUT a restart, after
     * {@code /cs set lodBackchannelPort}. Three things must happen together or the change is worse than
     * useless: the old listener stops (or the old port stays open and nothing has moved), the new one
     * binds, and every connected client is told and re-issued a token -- {@link CsLodHttpServer#stop()}
     * clears the token table, so a client that is not re-greeted holds a credential the new listener will
     * not honour and quietly 404s until it relogs. Main thread only: it sends packets.
     *
     * @return the port now bound, or 0 if the backchannel is not running (in-band fallback)
     */
    public static int rebind() {
        final MinecraftServer current = server;
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
        final Path root = LodSupport.storeRootBase(current);
        try {
            Files.createDirectories(root);
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: cannot create the LOD store root " + root + ": " + e);
            return 0;
        }
        http = new CsLodHttpServer(root, TOKENS, CsLodServerNet::isOnline);
        final int bound = http.start(current.getLocalIp(), current.getPort(), configuredPort());
        readvertise(current, bound);
        return bound;
    }

    /**
     * Re-send the hello to every client that has spoken the protocol, carrying the new port and a fresh
     * token. Only GREETED players: a vanilla client would log an unknown id and drop it. A player whose
     * send fails is left alone rather than retried -- they re-hello on their next join, and the in-band
     * channel keeps working meanwhile.
     */
    private static void readvertise(final MinecraftServer current, final int port) {
        final List<String> dims = dimensions();
        final boolean available = !dims.isEmpty();
        int told = 0;
        for (final ServerPlayer player : current.getPlayerList().getPlayers()) {
            if (!GREETED.contains(player.getUUID())) {
                continue;
            }
            final String token = (available && port != 0)
                    ? TOKENS.issue(player.getUUID(), addressOf(player))
                    : "";
            try {
                send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                        CsLodProtocol.VERSION, available, port, token, dims)));
                told++;
            } catch (final IOException e) {
                LOGGER.warn("Chunksmith: could not tell {} about the new backchannel port: {}",
                        nameOf(player), e.toString());
            }
        }
        LOGGER.info("Chunksmith: LOD backchannel moved to port {} -- {} connected client(s) re-issued"
                + " a token. No relog needed.", port == 0 ? "none (in-band)" : String.valueOf(port), told);
    }

    public static void onServerStopped() {
        // First: a rebind that fired after this point would resurrect a listener for a dying server.
        CsLodControl.clear();
        if (http != null) {
            http.stop();
            http = null;
        }
        final ExecutorService current = scanPool;
        if (current != null) {
            // A scan holds no lock the shutdown needs and writes nothing, but we wait a moment anyway so a
            // scan in flight is not interrupted mid-readdir into an otherwise clean shutdown.
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

    public static String describe() {
        final String inBand = CsLodInBandSender.pending() > 0
                ? " | in-band backlog: " + CsLodInBandSender.pending() + " regions" : "";
        return (http == null ? "LOD serving: in-band only (no backchannel)" : "LOD serving: " + http.describe())
                + inBand;
    }

    /**
     * Issue a backchannel token for an ONLINE player, out of band of the handshake -- so an operator can
     * mint a token and try the endpoint by hand. Op-gated, and still bound to that player's real address,
     * so it grants nothing the player could not already get by connecting.
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
            // ANSWER anyway, with our version and nothing else: a mismatched client that hears nothing back
            // cannot tell "no Chunksmith here" from "will not talk to me". One 30-byte reply and the old
            // client's own version check NAMES the problem in their log. No token, no scan, no data served.
            send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                    CsLodProtocol.VERSION, false, 0, "", List.of())));
            return;
        }
        if (!hello.hasVoxy() && !hello.hasDh()) {
            // No renderer, no DATA. Answer with an empty hello and stop before the store is even looked at:
            // no token minted, no dimension list built, no radius recorded, and deliberately NOT added to
            // WAITING, so storeWatchTick never wakes them with an offer they cannot accept.
            send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                    CsLodProtocol.VERSION, false, 0, "", List.of())));
            // But DO record the greeting (3.4.0): GREETED is what hasLodClient() answers, which decides
            // whether /cslod set relays to them -- leaving it out was why a player with Chunksmith and no
            // renderer could not reach their own client settings at all. Guarded by add(): one line/session.
            if (GREETED.add(player.getUUID())) {
                LOGGER.info("Chunksmith: LOD hello from " + nameOf(player)
                        + " (voxy=false dh=false -- no LOD renderer) -> serving no data;"
                        + " /cslod set can reach them");
            }
            return;
        }

        final List<String> dims = dimensions();
        final boolean available = !dims.isEmpty();
        final int port = http == null ? 0 : http.getPort();

        // The token is issued HERE, over a connection Mojang has already authenticated: a UUID or a name
        // proves nothing (both are public), but only a genuinely joined player can receive this. And ONLY
        // when there is something to serve. "The store DIRECTORY exists" used to be enough, so a server
        // minted a token the instant a pregen created the folder and before it wrote a single region -- a
        // credential to download nothing, and an operator reading "1 live token, 0 files". No data, no token.
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
                    ignored -> ConcurrentHashMap.newKeySet()).addAll(dims);
        } else {
            // Nothing for them yet. REMEMBER them: the store usually fills up later in this very session.
            WAITING.add(player.getUUID());
        }

        // The client re-asks on a backed-off clock and again to renew its token. Narrate only the FIRST
        // hello of a session: a line every fifteen seconds per waiting player is how a feature gets disabled.
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
     * The fallback: no backchannel port is open, so we drip the regions down the game connection instead.
     * Slow on purpose -- gameplay wins, LOD fills the gaps.
     */
    private static void inBand(final ServerPlayer player, final DataInputStream in) throws IOException {
        final String requested = in.readUTF();
        final int count = in.readInt();
        // Bound BEFORE sizing anything: count came off the wire (see CsLodIndexScan.MAX_REGIONS).
        if (count < 0 || count > CsLodIndexScan.MAX_REGIONS) {
            LOGGER.warn("Chunksmith: ignoring an in-band LOD request from {} for {} regions (max {})",
                    nameOf(player), count, CsLodIndexScan.MAX_REGIONS);
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
        // The dimension came off the wire and is about to build filesystem paths -- validate + contain it.
        if (safeDimensionDir(root, requested) == null) {
            LOGGER.warn("Chunksmith: ignoring an in-band LOD request from {} for a malformed dimension id",
                    nameOf(player));
            return;
        }
        // Same rule as the index (see dispatch()): serve the dimension the player is IN, whatever they
        // asked for. The sender stamps it on every slice and the client files under the dimension it is told.
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
     * Tell the players who joined before the store existed, once it does. The client still PULLS: we
     * re-send the HELLO, the same message we answer a hello with, and the client decides for itself
     * whether to ask for an index. Deliberately cheap and quiet -- no watcher thread, no
     * {@code WatchService}, no filesystem poll at all unless a player is actually waiting (a normal server
     * pays one {@code isEmpty()} per tick); at most one notice per player per dimension per session
     * ({@link #ANNOUNCED}), so a pregen writing thousands of regions produces one message; and the player
     * leaves {@link #WAITING} the moment they are told, so the watch goes back to sleep.
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
            final Set<String> told = ANNOUNCED.computeIfAbsent(uuid,
                    ignored -> ConcurrentHashMap.newKeySet());
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

    public static void sendTo(final ServerPlayer player, final byte[] data) {
        send(player, data);
    }

    /**
     * Has this player's client actually spoken the LOD protocol to us? The hello is the only honest signal
     * that there is a Chunksmith on the other end, and it matters for {@code /cslod set}: an unknown
     * message id is logged and dropped at the far end silently, so without this check a player on a vanilla
     * client would type a command and have no way to tell "it worked" from "nothing is listening". <b>A
     * renderer is not required to be greeted</b> (3.4.0) -- the question is "is there a Chunksmith
     * listening?", not "is there anything to draw with?".
     */
    public static boolean hasLodClient(final ServerPlayer player) {
        return GREETED.contains(player.getUUID());
    }

    /**
     * Ask a player's client to list, show or set one of its OWN LOD settings. Main thread only, like every
     * other send. The client prints the reply into its own chat; this side reports nothing about the
     * outcome because it cannot know it -- the file being written is on the player's machine.
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
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: could not encode a client-setting message: {}", e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ index + summary

    /**
     * Everything the scan thread needs, captured on the MAIN thread. This record is the thread boundary:
     * a player's position, their level and the player object itself are all mutated by the tick, so we read
     * them once synchronously on the tick and the scan thread then works from an immutable snapshot and
     * never touches a game object again.
     *
     * @param summaryOnly true for a sync poll (fold the answer to two numbers), false for a full index
     */
    private record Request(UUID uuid, String name, String dimension, int px, int pz, int radius,
                           boolean summaryOnly) {
    }

    /**
     * Take the snapshot, and hand the filesystem work to the scan thread. ALWAYS called on the server main
     * thread; it is the last thing on the main thread this feature does, and everything here is O(1).
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

        // AN INDEX IS ONLY MEANINGFUL FOR THE DIMENSION THE PLAYER IS STANDING IN: it is filtered by the
        // renderer's radius measured from THEIR position, and a position is a position in a particular
        // world. A 3.1.0-beta-2 client latched onto the first dimension we listed at join and never asked
        // for another; we cannot patch a jar already in a player's mods folder, but we do not have to
        // honour a request we know is wrong. Serve the dimension they are ACTUALLY in, and echo which.
        final String dimension = dimensionOf(player);
        if (dimension.isEmpty()) {
            return;
        }
        if (!dimension.equals(requested)) {
            LOGGER.info("Chunksmith: {} asked for the LOD index of {} while standing in {} -- serving {}"
                    + " instead.", nameOf(player), requested, dimension, dimension);
        }

        final UUID uuid = player.getUUID();
        // One scan per player at a time (see SCANNING): what keeps the scan queue bounded.
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
     * The scan -- on the scan thread, never on the tick. Readdir the dimension directory, stat the regions
     * that are in range, and either send the whole index or fold it to two numbers. Not one byte of any
     * region file is read.
     */
    private static void run(final Path root, final Request request) {
        try {
            final Path dir = safeDimensionDir(root, request.dimension());
            if (dir == null) {
                return;
            }
            final CsLodIndexScan.Result scanned = CsLodIndexScan.scan(dir,
                    new CsLodIndexScan.Request(request.dimension(), request.px(), request.pz(),
                            request.radius()), System.currentTimeMillis());
            if (scanned.capped()) {
                LOGGER.warn("Chunksmith: LOD index for {} capped at {} of {} regions ({} MB of a {} MB"
                                + " budget, radius {}). The client re-requests as the player moves, so it"
                                + " gets the rest as it travels -- nearest regions first.",
                        request.name(), scanned.regions().size(), scanned.found(),
                        scanned.bytes() / (1024 * 1024), CsLodIndexScan.MAX_BYTES / (1024 * 1024),
                        request.radius());
            }
            final List<CsLodMessages.RegionEntry> regions = scanned.regions();

            final byte[] message;
            if (request.summaryOnly()) {
                message = CsLodMessages.encode(new CsLodMessages.RegionSummary(
                        request.dimension(), regions.size(), CsLodIndexScan.aggregate(regions)));
                LOGGER.debug("Chunksmith: LOD sync summary for {} -- {} regions of {}",
                        request.name(), regions.size(), request.dimension());
            } else {
                message = CsLodMessages.encode(new CsLodMessages.RegionIndex(request.dimension(), regions));
            }

            // Back to the main thread to SEND: the send is the one part of this that touches a live player
            // object, and hopping back costs a queue entry and buys not reasoning about channel thread-safety.
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
     * Resolve a wire dimension id to a directory INSIDE the store, or null if it is malformed or tries to
     * escape. Two gates, same as {@code CsLodHttpServer.resolve}: the shape must match {@link #DIM_DIR},
     * AND the normalized result must still start with the store root (catching a "." / ".." the pattern
     * would otherwise admit).
     */
    private static Path safeDimensionDir(final Path root, final String dimension) {
        if (dimension == null || dimension.isEmpty() || !DIM_DIR.matcher(dimension).matches()) {
            return null;
        }
        final Path dir = root.resolve(dimension).normalize();
        return dir.startsWith(root) ? dir : null;
    }

    /**
     * The dimensions we can actually SERVE, right now. A dimension DIRECTORY is not LOD data: a pregen
     * creates {@code chunksmith/lod/<dim>/} the instant it starts and writes no region into it for some
     * time after, and advertising it then told clients we had a dimension we could not serve one byte of --
     * and minted a backchannel token for it (see {@link #hello}). {@link CsLodStoreScan} stops at the first
     * region file it finds.
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
     * The store key of the dimension the player is ACTUALLY in -- the authority for everything we serve
     * them. Resolved by identity against the server's own levels, so it is the same string
     * {@link LodSupport#storeRoot} named that dimension's directory with. The empty return is unreachable
     * in practice; it exists so a caller can never get a plausible-looking wrong answer.
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
