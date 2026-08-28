package com.kishku7.chunksmith.lod.client.net;

import com.kishku7.chunksmith.lod.client.CsLodCache;
import com.kishku7.chunksmith.lod.client.CsLodClientConfig;
import com.kishku7.chunksmith.lod.client.CsLodClientSettings;
import com.kishku7.chunksmith.lod.client.CsLodManifest;
import com.kishku7.chunksmith.lod.client.CsLodDimension;
import com.kishku7.chunksmith.lod.client.CsLodDownloader;
import com.kishku7.chunksmith.lod.client.CsLodStore;
import com.kishku7.chunksmith.lod.client.Renderers;
import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import com.kishku7.chunksmith.lod.net.CsLodRetry;
import com.kishku7.chunksmith.lod.net.CsLodSummary;
import com.kishku7.chunksmith.lod.client.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client side of the Chunksmith LOD protocol.
 *
 * <p>The client drives the exchange: on join it says hello, announcing which renderers it has and the
 * radius its renderer draws (the server follows that number); the server answers with store availability,
 * the backchannel port and a token; the client asks for the region index, diffs it against the local
 * store, fetches only what it lacks, hands it to the renderer -- and keeps doing so as the player travels
 * ({@link #travelTick}). <b>An empty store at join is not the end of the session</b>: the old client asked
 * once, was told "nothing here" and stood down for good, but an operator starting an hours-long pregen
 * with players already connected is the NORMAL case, so we keep asking on a backed-off clock
 * ({@link CsLodRetry}) and a Chunksmith server also re-sends its hello the moment its store becomes
 * servable. No relog either way.
 *
 * <p>Loader-blind: channel registration, sends, join/disconnect/tick and the game directory all go through
 * {@link com.kishku7.chunksmith.lod.client.ClientPlatform} -- SAME source on Fabric and NeoForge. The fast
 * path always wins when it is there: every fetch goes over the HTTP backchannel if the server advertised a
 * port, in-band only when there is no port or the advertised one is unreachable (firewalled), which we
 * discover by trying it and getting nothing back.
 */
public final class CsLodClientNet {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /**
     * How far the player must travel before we ask what is now in range. Half a region: the server indexes
     * by REGION (512 blocks), so a shorter trigger cannot bring anything new into range and a much longer
     * one lets the player outrun their own horizon.
     */
    private static final double REFRESH_MOVE_BLOCKS = 256.0;

    /** Never re-ask faster than this, however fast the player is moving (elytra, /tp, boats on ice). */
    private static final long MIN_REFRESH_MILLIS = 5_000L;

    /**
     * Re-handshake before the backchannel token can go stale. A token lives ten minutes, a session lives
     * hours: a travel refresh an hour on would present an expired one, 403 on every fetch, and drop
     * silently to the in-band fallback for the rest of the session. Three quarters of the lifetime, so it
     * is always replaced well before it dies.
     */
    private static final long TOKEN_REFRESH_MILLIS = CsLodProtocol.TOKEN_TTL_MILLIS / 4L * 3L;

    private static volatile CsLodDownloader downloader;
    private static volatile String token = "";
    private static volatile long tokenIssuedMillis;
    private static volatile int backchannelPort;
    private static volatile String host = "";

    /**
     * The dimension we are currently pulling for. ALWAYS the one the player is actually in. The
     * 3.1.0-beta-2 bug: it was set to {@code hello.dimensions().get(0)} -- the overworld on every normal
     * server -- and never changed again, so after a Nether portal the client kept pulling the OVERWORLD's
     * index and store and handing those records to the injector for the level the player was now in, while
     * every counter reported success. Now re-derived from the LEVEL ({@link #dimensionTick}); a dimension
     * change clears it and re-arms the exchange. Empty means "not pulling for anything".
     */
    private static volatile String activeDimension = "";

    /**
     * The dimension the player was in when we last looked; a difference from the level IS the dimension
     * change (no reliable cross-loader, cross-version dimension-change event exists, and the level is truth).
     */
    private static volatile String playerDimension = "";

    /** What the server told us it can serve. Re-read on every hello, so a later pregen shows up here. */
    private static volatile List<String> serverDimensions = List.of();

    /** The server answered and had nothing to give us -- yet. The state the old client could not leave. */
    private static volatile boolean awaitingStore;

    /** How long to wait before asking an empty-store server again. Backs off; reset on disconnect. */
    private static final CsLodRetry RETRY = new CsLodRetry();

    /**
     * What we told the server we can draw, cached from the FIRST hello and re-used by every later one.
     * Not just an optimization: {@code Renderers.configuredRadiusBlocks()} reaches into voxy's config, and
     * the join handshake is the only place it is safe to do that (see {@link #hello}) -- a retry must not
     * go back and ask voxy again.
     */
    private static volatile boolean capsVoxy;
    private static volatile boolean capsDh;
    private static volatile int capsRadius;

    /** One fetch at a time. A travel refresh must never race the join fetch, or itself. */
    private static final AtomicBoolean busy = new AtomicBoolean();

    /**
     * How long to wait for an answer to our hello before saying, once, that none came. See
     * {@link #silenceTick} for why the causes of silence are indistinguishable on the wire.
     */
    private static final long HELLO_TIMEOUT_MILLIS = 10_000L;

    /**
     * The entries of the LAST index the server gave us -- the set the sync poll folds over (see
     * {@link #summary}). Deliberately the SERVER's answer rather than a listing of our own store: the
     * server excludes regions its pregen is still writing, so folding our own directory would disagree on
     * every poll and pull a full index every interval for the entire length of a pregen.
     */
    private static volatile List<CsLodMessages.RegionEntry> lastIndex = List.of();

    /** When we last asked "has anything changed?". Reset by a real index -- there is no point asking twice. */
    private static volatile long lastSyncMillis;

    /** When we said hello, and whether anything ever came back. For the one-shot silence notice. */
    private static volatile long helloSentMillis;
    private static volatile boolean helloAnswered;
    private static volatile boolean silenceReported;

    private static volatile double lastIndexX;
    private static volatile double lastIndexZ;
    private static volatile long lastIndexMillis;

    /** In-band fallback state: where the slices are being assembled, and for which dimension. */
    private static volatile Path inBandRoot;
    private static volatile String inBandDimension = "";
    private static volatile List<CsLodMessages.RegionEntry> inBandRegions = List.of();
    private static volatile CsLodManifest inBandManifest;
    private static final Map<String, java.io.ByteArrayOutputStream> PARTIAL = new java.util.HashMap<>();

    private CsLodClientNet() {
    }

    public static void register() {
        ClientPlatform.registerClientNetworking(CsLodClientNet::handle);
        ClientPlatform.onJoin(CsLodClientNet::hello);
        ClientPlatform.onDisconnect(CsLodClientNet::reset);
        ClientPlatform.onClientTick(CsLodClientNet::travelTick);
    }

    /**
     * Ask again for what is in range, as the player travels. The server's index is filtered by the radius
     * we announced, measured from the player's CURRENT position, so the same request sent from somewhere
     * else returns a different answer. The index is a few hundred bytes and the diff means we ask only for
     * what is genuinely new, so standing still costs nothing at all.
     */
    private static void travelTick() {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // BEFORE anything else: are we still in the dimension we think we are? Checked even while a fetch
        // is in flight -- a fetch for the dimension the player has just LEFT is what must be stopped.
        if (dimensionTick()) {
            return;
        }
        if (busy.get()) {
            return;
        }
        if (activeDimension.isEmpty()) {
            // Nothing to refresh yet -- but if the server told us its store was empty (or had nothing for
            // the dimension we are in), keep asking; that may not be true any more.
            retryTick();
            silenceTick();
            return;
        }

        // THE SYNC POLL, ON ITS OWN CLOCK -- deliberately above every movement test below, which only fire
        // once the player has travelled half a region. A player who joins, walks to their base and stays
        // there used to get nothing more for the whole session. So we ask for two numbers (22 bytes out,
        // 34 back), never an index: an index every few minutes from every client rebuilds the memory bug.
        syncTick();

        final long now = System.currentTimeMillis();
        if (now - lastIndexMillis < MIN_REFRESH_MILLIS) {
            return;
        }
        final double dx = player.getX() - lastIndexX;
        final double dz = player.getZ() - lastIndexZ;
        if (dx * dx + dz * dz < REFRESH_MOVE_BLOCKS * REFRESH_MOVE_BLOCKS) {
            return;
        }
        // A refresh is due. If the token is getting old, renew it FIRST and let the server's answer drive
        // this refresh. Stamp the clock before we send, or we re-send on every tick until the answer lands.
        if (backchannelPort != 0 && !token.isEmpty() && now - tokenIssuedMillis >= TOKEN_REFRESH_MILLIS) {
            lastIndexMillis = now;
            lastIndexX = player.getX();
            lastIndexZ = player.getZ();
            LOGGER.debug("Chunksmith: renewing the backchannel token before this travel refresh");
            sendHello(false);
            return;
        }
        requestIndex(activeDimension);
    }

    /**
     * Did the player just change dimension? If so, re-arm the whole exchange for the level they are now in.
     *
     * @return true if the dimension changed (the caller must do nothing else this tick)
     */
    private static boolean dimensionTick() {
        final String now = CsLodDimension.current();
        if (now.isEmpty()) {
            // Mid-change: the old level is gone and the new one is not up. Wait for the next tick.
            return false;
        }
        if (now.equals(playerDimension)) {
            return false;
        }

        final String from = playerDimension;
        playerDimension = now;

        // Whatever we were pulling was for the level the player has just LEFT: an index or download that
        // lands now describes somewhere they no longer are, and the injector will refuse it. Cancel the
        // in-flight fetch so we are not holding the busy latch against the dimension we are about to ask for.
        activeDimension = "";
        inBandRoot = null;
        inBandDimension = "";
        inBandRegions = List.of();
        inBandManifest = null;
        // The last index described the dimension the player has just LEFT. Folding a sync answer against it
        // would compare the Nether's summary with the overworld's regions.
        lastIndex = List.of();
        lastSyncMillis = 0L;
        PARTIAL.clear();
        final CsLodDownloader current = downloader;
        if (current != null) {
            current.cancel();
        }
        if (busy.get()) {
            // Tell the server to stop too. An in-band drip-feed has no downloader to cancel, and left
            // running it would keep spending the gameplay connection on a dimension the player has left.
            send(CsLodMessages.cancel());
        }
        downloader = null;
        busy.set(false);
        lastIndexMillis = 0L;

        if (!capsVoxy && !capsDh) {
            // No renderer. hello() already said so; do not narrate a dimension change we will do nothing about.
            return true;
        }
        if (from.isEmpty()) {
            // First level of the session -- not a CHANGE, just us learning where we started. hello() is
            // already on its way; record it and let the normal handshake arm us.
            return true;
        }

        LOGGER.info("Chunksmith: the player moved from {} to {} -- the LOD data for {} is a DIFFERENT world,"
                + " so asking the server what it has for {}", from, now, from, now);

        // Ask again. The hello is the message the server already answers on join, so this needs no new
        // packet: it comes back with a fresh token and serverHello() arms us for the dimension we are in.
        awaitingStore = false;
        RETRY.reset();
        sendHello(true);
        return true;
    }

    /**
     * "Has anything changed?" -- once per configured interval, whatever the player is doing. One poll, for
     * the store that started all this (340 regions, 1567 MB, a 4-region radius, 81 regions in range): 22
     * bytes out, 34 bytes back, and ~86 server-side syscalls on a background thread with ZERO bytes of file
     * content -- one {@code openat} + ~3 {@code getdents64} + one {@code close} to list the 340 names, then
     * one {@code statx} per region actually in range (the name and radius tests both run before the stat).
     * mtime and size come out of that stat, and they ARE the freshness token now. One INDEX in
     * 3.1.0-beta-3 cost 366.9 MB read into the heap, every buffer G1-humongous, on the server main thread.
     */
    private static void syncTick() {
        if (!CsLodClientConfig.isLoaded()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastSyncMillis < CsLodClientConfig.syncIntervalMillis()) {
            return;
        }
        // Stamp BEFORE sending, or a slow answer means we re-ask on every tick until it lands.
        lastSyncMillis = now;
        try {
            send(CsLodMessages.requestSummary(activeDimension));
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: failed to ask the server for a LOD summary: {}", e.toString());
        }
    }

    /**
     * The server folded its in-range index to two numbers. Fold OURS the same way and compare. Equal means
     * the server holds exactly the regions it last told us about, at exactly those versions, and we hold
     * every one -- the 99% case, and it must be free. Different means one of three things, and all three
     * have the same answer (pull the index and let the diff work it out): the server's store GREW, or WE
     * lost regions (deleted, truncated, a disk that lied), or a region we hold CHANGED so its recorded
     * token no longer matches the advertised one. Runs off the game thread -- a stat per region of the last
     * index, and the game thread does not wait for a disk for anything, ever.
     */
    private static void summary(final CsLodMessages.RegionSummary summary) {
        final String dimension = summary.dimension();
        if (!dimension.equals(activeDimension)) {
            // The answer to a question we asked from a dimension we have since left. Drop it.
            return;
        }
        final Path root = storeRoot();
        final Path dir = CsLodStore.dimensionDir(root, dimension);
        if (dir == null) {
            LOGGER.warn("Chunksmith: server sent a malformed dimension id in a LOD summary; ignoring it");
            return;
        }
        final List<CsLodMessages.RegionEntry> mine = lastIndex;

        final Thread worker = new Thread(() -> {
            final CsLodManifest manifest = CsLodManifest.open(root, dimension);
            if (manifest == null) {
                return;
            }
            final CsLodSummary.Snapshot ours = manifest.fold(dir, mine);
            if (ours.count() == summary.count() && ours.aggregate() == summary.aggregate()) {
                LOGGER.debug("Chunksmith: LOD sync -- nothing has changed ({} regions of {})",
                        ours.count(), dimension);
                return;
            }
            LOGGER.info("Chunksmith: LOD sync -- {} no longer matches the server (it has {} regions in my"
                            + " radius, I can vouch for {}). Pulling the index and fetching only the"
                            + " difference. No relog, and I did not have to move.",
                    dimension, summary.count(), ours.count());
            Minecraft.getInstance().execute(() -> {
                // Re-check on the game thread: the player may have walked through a portal while we statted.
                if (dimension.equals(activeDimension)) {
                    requestIndex(dimension);
                }
            });
        }, "chunksmith-lod-sync");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * We said hello and nothing came back. Say so ONCE, at INFO. Two servers look identical from here and
     * neither sends anything that would tell them apart: one that does not run Chunksmith at all (normal,
     * nothing wrong), and one running 3.1.0-beta-3 or earlier, which sees our v2 hello, refuses it as a
     * protocol it does not know, and replies with nothing. (Our own v2 server deliberately answers an old
     * client's v1 hello so it can name the mismatch. An old server does us no such favour.) INFO rather
     * than DEBUG because case 2 is a player who updated before the server did, staring at an empty horizon
     * with no explanation in their log; the line is worded to be true of both cases and does not guess.
     */
    private static void silenceTick() {
        if (helloAnswered || silenceReported || helloSentMillis == 0L || host.isEmpty()) {
            return;
        }
        // Every word below is about LOD TERRAIN the player expected to see. With no renderer our hello went
        // out to reach /cslod set rather than to ask for data (see hello()), so silence is not that failure.
        if (!capsVoxy && !capsDh) {
            return;
        }
        if (System.currentTimeMillis() - helloSentMillis < HELLO_TIMEOUT_MILLIS) {
            return;
        }
        silenceReported = true;
        LOGGER.info("Chunksmith: no LOD data is being offered by this server (it did not answer our hello"
                        + " within {}s). Either it does not run Chunksmith -- which is normal, and nothing is"
                        + " wrong -- or it runs a version older than 3.1.0-beta-4, which speaks an LOD"
                        + " protocol older than v{} and cannot serve this client. If you expected LOD terrain"
                        + " here, the server and every client must be on 3.1.0-beta-4 or later.",
                HELLO_TIMEOUT_MILLIS / 1000L, CsLodProtocol.VERSION);
    }

    /**
     * The server had nothing for us. Ask again on a backing-off clock: one ~10-byte packet at 15s, 30s,
     * 60s, then every two minutes. The entire price of never losing a session to "you joined too early".
     */
    private static void retryTick() {
        if (!awaitingStore) {
            return;
        }
            // Singleplayer. The world's own injector already draws these LODs directly, so this path would
            // be a DUPLICATE -- and a duplicate is the last thing that should be put on a timer.
        if (host.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!RETRY.due(now)) {
            return;
        }
        RETRY.attempted(now);
        // Loud for the first couple of minutes so the log SHOWS we are still trying; quiet after that.
        final String line = "Chunksmith: the server still has no LOD data (asked " + RETRY.attempts()
                + " more time(s)); asking again in " + RETRY.delayMillis() / 1000L + "s";
        if (RETRY.attempts() <= 3) {
            LOGGER.info(line);
        } else {
            LOGGER.debug(line);
        }
        sendHello(false);
    }

    /**
     * Tell the server what we can render, and how far. The join handshake. <b>We say hello even with NO
     * renderer installed</b> (3.4.0): {@code /cslod set} is a SERVER command that only relays to a client
     * the server has heard from ({@code CsLodServerNet.hasLodClient}), so returning here silently made the
     * two settings in {@code config/chunksmith-lod.properties} unreachable in-game for exactly the players
     * most likely to be fiddling with them. The hello carries {@code hasVoxy}/{@code hasDh} both FALSE and
     * the server answers with an empty hello and serves no data. Loading the config here is load-bearing:
     * {@link CsLodClientConfig} only learns WHERE its file lives from this call, and until it does a
     * {@code /cslod set} would change the value in memory and write no file at all.
     */
    private static void hello() {
        final boolean voxy = Renderers.hasVoxy();
        final boolean dh = Renderers.hasDh();
        final Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() != null) {
            host = client.getCurrentServer().ip;
            final int colon = host.lastIndexOf(':');
            if (colon > 0) {
                host = host.substring(0, colon);
            }
        }
        // Read the renderers' radius HERE and nowhere earlier: this is the first moment both renderers are
        // up, and asking voxy sooner (e.g. from the init status line) class-loads its config before voxy
        // initializes and leaves voxy inert for the whole session -- silently. See VoxyRadius. Cache it;
        // every later hello re-uses these numbers rather than going back to voxy's config.
        capsVoxy = voxy;
        capsDh = dh;
        capsRadius = Renderers.configuredRadiusBlocks();
        // The sync interval, with its floor applied IN CODE (see CsLodClientConfig -- "sync-interval-
        // seconds=1" must not become a poll storm against a server trying to run a pregen).
        LOGGER.info("Chunksmith: {}", CsLodClientConfig.load(ClientPlatform.gameDir().resolve("config")));
        if (!voxy && !dh) {
            LOGGER.info("Chunksmith: hello -- no LOD renderer installed (voxy / Distant Horizons), so no"
                    + " LOD data will be requested or drawn. Saying hello anyway so that /cslod set can"
                    + " reach this client's settings.");
        } else {
            LOGGER.info("Chunksmith: hello -- voxy={} dh={} radius={} blocks", voxy, dh, capsRadius);
        }
        // Name the DH the player ACTUALLY has, at join: we compile against the standalone
        // distanthorizonsapi artifact and support a wide range of DH releases. DhTarget hard-references DH
        // types, so only touch it when DH is really present.
        if (dh) {
            LOGGER.info("Chunksmith: feeding {}", com.kishku7.chunksmith.lod.client.render.DhTarget.version());
        }
        sendHello(true);
    }

    /**
     * Put a hello on the wire. Three callers -- the join handshake, an empty-store retry and a token
     * renewal -- and the server answers every one identically with its current hello, which is why none of
     * this needed a new packet id. An older server answers a repeat hello exactly as it answered the first.
     */
    private static void sendHello(final boolean first) {
        try {
            send(CsLodMessages.encode(new CsLodMessages.ClientHello(
                    CsLodProtocol.VERSION, capsVoxy, capsDh, capsRadius)));
            if (first) {
                RETRY.started(System.currentTimeMillis());
                helloSentMillis = System.currentTimeMillis();
            }
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: failed to say hello: " + e);
        }
    }

    private static void handle(final byte[] data) {
        if (data.length == 0) {
            return;
        }
        try (DataInputStream in = CsLodMessages.reader(data)) {
            final byte id = in.readByte();
            switch (id) {
                case CsLodProtocol.S2C_HELLO -> serverHello(CsLodMessages.decodeServerHello(in));
                case CsLodProtocol.S2C_SUMMARY -> summary(CsLodMessages.decodeRegionSummary(in));
                case CsLodProtocol.S2C_INDEX -> index(CsLodMessages.decodeRegionIndex(in));
                case CsLodProtocol.S2C_CHUNK -> slice(CsLodMessages.decodeRegionSlice(in));
                case CsLodProtocol.S2C_CLIENT_SETTING ->
                        clientSetting(CsLodMessages.decodeClientSetting(in));
                case CsLodProtocol.S2C_DONE -> {
                    LOGGER.info("Chunksmith: in-band transfer complete");
                    // One manifest write for the whole transfer, not one per region.
                    final CsLodManifest manifest = inBandManifest;
                    if (manifest != null) {
                        try {
                            manifest.save();
                        } catch (final IOException e) {
                            LOGGER.warn("Chunksmith: could not write the region manifest ({}); these"
                                    + " regions will be re-fetched next session", e.toString());
                        }
                    }
                    if (inBandRoot != null) {
                        injectAsync(inBandRoot, inBandDimension, inBandRegions);
                    } else {
                        busy.set(false);
                    }
                }
                default -> LOGGER.debug("Chunksmith: unhandled LOD message " + id);
            }
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: malformed LOD message: " + e);
        }
    }

    private static void serverHello(final CsLodMessages.ServerHello hello) {
        helloAnswered = true;
        if (hello.protocolVersion() != CsLodProtocol.VERSION) {
            // A 3.1.0-beta-3 server speaks v1, where the hash field is a CRC32 of the region's CONTENTS --
            // which is what forced that server to read every file in our radius (the bug). The two
            // protocols cannot interoperate; say so in words a player can act on, once.
            LOGGER.warn("Chunksmith: this server speaks LOD protocol v{} and we speak v{} -- not fetching."
                            + " The server and the client must be on the same Chunksmith version"
                            + " (v1 is 3.1.0-beta-3 and earlier; v2 is 3.1.0-beta-4 and later).",
                    hello.protocolVersion(), CsLodProtocol.VERSION);
            return;
        }
        if (!capsVoxy && !capsDh) {
            // No renderer. Our hello was an introduction so /cslod set can reach us, and it has been
            // answered. Do NOT arm a dimension, request an index, or enter the empty-store retry loop
            // against a server that is quite right not to send us anything.
            LOGGER.debug("Chunksmith: the server answered our hello; with no renderer installed nothing"
                    + " will be fetched, but /cslod set can now reach this client");
            return;
        }
        if (!hello.storeAvailable() || hello.dimensions().isEmpty()) {
            // NOT the end of the session: the operator has almost certainly not run the pregen yet, and
            // when they do we want the player to see it without being told to relog. (In singleplayer the
            // world's own injector covers this state, so retryTick stays out of it.)
            if (!awaitingStore) {
                awaitingStore = true;
                LOGGER.info("Chunksmith: the server has no pregenerated LOD data yet."
                        + " Staying connected and checking again (every {}s at first, then every {}s)"
                        + " -- it will arrive on its own if the operator pregenerates, and you do NOT"
                        + " need to relog.",
                        CsLodRetry.FIRST_DELAY_MILLIS / 1000L, CsLodRetry.MAX_DELAY_MILLIS / 1000L);
            }
            return;
        }
        token = hello.token();
        tokenIssuedMillis = System.currentTimeMillis();
        backchannelPort = hello.backchannelPort();
        serverDimensions = hello.dimensions();

        // WHICH dimension? The one the player is standing in -- NEVER the first one the server listed.
        // That single line was the 3.1.0-beta-2 bug; see the note on activeDimension.
        final String mine = CsLodDimension.current();
        if (mine.isEmpty()) {
            // No level yet -- still loading in. dimensionTick() re-hellos once there is a level to name.
            return;
        }
        playerDimension = mine;

        if (!serverDimensions.contains(mine)) {
            // The server HAS data -- just not for the dimension we are in. Normal (most operators pregen
            // only the overworld), and NOT a reason to render another dimension's terrain here.
            if (!awaitingStore) {
                awaitingStore = true;
                LOGGER.info("Chunksmith: the server has LOD data for {}, but nothing for {} -- the"
                                + " dimension you are in. Nothing will be drawn here (data from another"
                                + " dimension is NOT a substitute). Checking again as you play.",
                        serverDimensions, mine);
            }
            activeDimension = "";
            return;
        }

        if (awaitingStore) {
            LOGGER.info("Chunksmith: the server NOW has LOD data for {} -- fetching it (after {} check(s),"
                            + " with no relog)", mine, RETRY.attempts());
            awaitingStore = false;
            RETRY.reset();
        }

        // Only ANNOUNCE on the hello that ARMS us for this dimension. The later ones are token renewals,
        // and a renewal every few minutes of travel must not re-narrate the connection.
        final boolean arming = activeDimension.isEmpty();
        if (backchannelPort == 0) {
            // The operator has not opened the port. Not an error: we ask in-band instead, which is much
            // slower -- it rides the gameplay connection -- but works everywhere.
            if (arming) {
                LOGGER.info("Chunksmith: server has LOD data for {} but no backchannel; using the in-band"
                        + " fallback (slower)", mine);
            }
        } else if (arming) {
            LOGGER.info("Chunksmith: server has LOD data for {}; backchannel on port {}",
                    mine, backchannelPort);
        }
        activeDimension = mine;
        requestIndex(mine);
    }

    /** Ask what is in range right now, and remember where we asked from. */
    private static void requestIndex(final String dimension) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastIndexX = player.getX();
            lastIndexZ = player.getZ();
        }
        lastIndexMillis = System.currentTimeMillis();
        try {
            send(CsLodMessages.requestIndex(dimension));
        } catch (final IOException e) {
            busy.set(false);
            LOGGER.warn("Chunksmith: failed to request the region index: {}", e.toString());
        }
    }

    private static void index(final CsLodMessages.RegionIndex index) {
        final Path root = storeRoot();
        // The dimension is server-supplied and becomes a filesystem path in every transport below. Gate it
        // once at the top too, and free the busy latch we took to get here.
        if (CsLodStore.dimensionDir(root, index.dimension()) == null) {
            LOGGER.warn("Chunksmith: server sent a malformed dimension id; ignoring the region index");
            busy.set(false);
            return;
        }
        // REMEMBER IT. This is the set the sync poll folds against (see summary()), and it carries each
        // region's freshness token to the injector -- a region whose token has MOVED must be re-injected,
        // not skipped as "already drawn". Bare coordinates, as we used to carry, would throw those away.
        lastIndex = index.regions();
        lastSyncMillis = System.currentTimeMillis();

        if (backchannelPort == 0 || token.isEmpty() || host.isEmpty()) {
            inBand(index, root);
            return;
        }

        downloader = new CsLodDownloader(root);

        // Off the game thread: a download must never make the game stutter. Injection follows on the same thread.
        final Thread worker = new Thread(() -> {
            try {
                // ONE cheap probe before we queue anything: an advertised-but-unreachable port costs a full
                // connect timeout PER REGION -- ~30s of dead air on a 9-region store before the fallback
                // fires, and it scales with the store. A single socket answers the same question in 2s.
                if (!reachable(host, backchannelPort)) {
                    LOGGER.warn("Chunksmith: the backchannel on port {} is advertised but unreachable;"
                            + " falling back to the in-band channel (slower)", backchannelPort);
                    backchannelPort = 0;
                    Minecraft.getInstance().execute(() -> inBand(index, root));
                    return;
                }

                final CsLodDownloader current = downloader;
                current.download(host, backchannelPort, token, index,
                        line -> LOGGER.info("Chunksmith: {}", line));

                // Backstop: the port ANSWERED a socket but every fetch still failed (a proxy that accepts
                // and drops, a server that dies mid-transfer). Drop to the in-band channel, which rides a
                // connection we know works, and say why.
                if (current.fetched() == 0 && current.failed() > 0) {
                    LOGGER.warn("Chunksmith: the backchannel on port {} accepted a connection but"
                                    + " served nothing ({} regions failed); falling back to the in-band"
                                    + " channel (slower)", backchannelPort, current.failed());
                    backchannelPort = 0;
                    Minecraft.getInstance().execute(() -> inBand(index, root));
                    return;
                }

                // THE PATH ALMOST EVERY PLAYER IS ON. It injects inline here rather than through
                // injectAsync (already off the game thread, and the download has to finish first) -- and
                // that difference is what made the 3.3.0 stop-flag bug invisible: the mod_support #16 fix
                // added an arm() to injectAsync, the IN-BAND FALLBACK, and this call site never got one.
                // If a third injection call site is ever added it must go through injectRegions, nowhere else.
                com.kishku7.chunksmith.lod.client.render.LodInjector.injectRegions(
                        root, index.dimension(), index.regions(),
                        line -> LOGGER.info("Chunksmith: {}", line));
            } finally {
                busy.set(false);
            }
        }, "chunksmith-lod-client");
        worker.setDaemon(true);
        worker.start();
    }

    /** Can we actually open a socket to the advertised backchannel? Two seconds, once, off the game thread. */
    private static boolean reachable(final String address, final int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(address, port), 2_000);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * The slow path: ask for the regions down the game connection. Used when the server never opened a
     * backchannel port, or advertised one we cannot reach. Asks only for what we are actually missing,
     * exactly as the fast path does -- the cache rule does not change just because the transport did.
     */
    private static void inBand(final CsLodMessages.RegionIndex index, final Path root) {
        inBandRoot = root;
        inBandDimension = index.dimension();
        inBandRegions = index.regions();

        // The manifest is the cache check on BOTH transports, and it is where each region's freshness
        // token is recorded as the slices land, so the next index can tell what we hold.
        final CsLodManifest manifest = CsLodManifest.open(root, index.dimension());
        inBandManifest = manifest;

        final List<CsLodMessages.RegionEntry> wanted = new ArrayList<>();
        for (final CsLodMessages.RegionEntry entry : index.regions()) {
            if (!CsLodCache.have(root, index.dimension(), manifest, entry)) {
                wanted.add(entry);
            }
        }
        LOGGER.info("Chunksmith: in-band fetch -- {} regions within my radius, {} already cached,"
                        + " {} to fetch (this is the slow path)",
                index.regions().size(), index.regions().size() - wanted.size(), wanted.size());
        if (wanted.isEmpty()) {
            injectAsync(root, index.dimension(), index.regions());
            return;
        }
        try {
            send(CsLodMessages.requestRegions(index.dimension(), wanted));
        } catch (final IOException e) {
            busy.set(false);
            LOGGER.warn("Chunksmith: failed to request in-band regions: {}", e.toString());
        }
    }

    /**
     * Reassemble an in-band region file, slice by slice. Written to a .part file and MOVED into place only
     * when the last slice lands, so a transfer cut off half way can never be mistaken for a cached region
     * on the next join.
     */
    private static void slice(final CsLodMessages.RegionSlice slice) {
        final Path root = inBandRoot;
        if (root == null) {
            return;
        }
        final String key = slice.regionX() + "." + slice.regionZ();
        final java.io.ByteArrayOutputStream buffer =
                PARTIAL.computeIfAbsent(key, ignored -> new java.io.ByteArrayOutputStream());
        buffer.writeBytes(slice.data());

        if (!slice.last()) {
            return;
        }
        PARTIAL.remove(key);
        try {
            // slice.dimension() is a distinct wire value, so gate it here too (D20 -- every consumer validates).
            final Path dimDir = CsLodStore.dimensionDir(root, slice.dimension());
            if (dimDir == null) {
                LOGGER.warn("Chunksmith: dropping an in-band slice with a malformed dimension id");
                return;
            }
            final Path target = dimDir.resolve("r." + slice.regionX() + "." + slice.regionZ() + ".cslod");
            Files.createDirectories(target.getParent());
            final Path temp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(temp, buffer.toByteArray());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            // Record what the SERVER said about the region just assembled. The in-band REQUEST echoes
            // coordinates only, so the token has to come from the index that prompted the fetch.
            final CsLodManifest manifest = inBandManifest;
            final CsLodMessages.RegionEntry advertised = advertised(slice.regionX(), slice.regionZ());
            if (manifest != null && advertised != null) {
                manifest.put(slice.regionX(), slice.regionZ(), advertised.hash(), Files.size(target));
            }
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: failed to store in-band region {}: {}", key, e.toString());
        }
    }

    /** What the server told us about this region in the index that prompted the in-band fetch. */
    private static CsLodMessages.RegionEntry advertised(final int regionX, final int regionZ) {
        for (final CsLodMessages.RegionEntry entry : inBandRegions) {
            if (entry.regionX() == regionX && entry.regionZ() == regionZ) {
                return entry;
            }
        }
        return null;
    }

    /** Hand the new regions to the renderers, off the game thread. */
    private static void injectAsync(final Path root, final String dimension,
                                    final List<CsLodMessages.RegionEntry> regions) {
        // Nothing to arm: the injector reads the CURRENT session generation when it starts (LodInjector.SESSION).
        final Thread worker = new Thread(() -> {
            try {
                com.kishku7.chunksmith.lod.client.render.LodInjector.injectRegions(root, dimension, regions,
                        line -> LOGGER.info("Chunksmith: {}", line));
            } finally {
                busy.set(false);
            }
        }, "chunksmith-lod-inject");
        worker.setDaemon(true);
        worker.start();
    }

    public static void cancel() {
        final CsLodDownloader current = downloader;
        if (current != null) {
            current.cancel();
            send(CsLodMessages.cancel());
        }
    }

    public static String describe() {
        final CsLodDownloader current = downloader;
        return current == null ? "idle" : current.describe();
    }

    private static void reset() {
        cancel();
        downloader = null;
        token = "";
        tokenIssuedMillis = 0L;
        backchannelPort = 0;
        host = "";
        activeDimension = "";
        playerDimension = "";
        serverDimensions = List.of();
        awaitingStore = false;
        RETRY.reset();
        capsVoxy = false;
        capsDh = false;
        capsRadius = 0;
        busy.set(false);
        lastIndexMillis = 0L;
        lastSyncMillis = 0L;
        lastIndex = List.of();
        helloSentMillis = 0L;
        helloAnswered = false;
        silenceReported = false;
        inBandRoot = null;
        inBandDimension = "";
        inBandRegions = List.of();
        inBandManifest = null;
        PARTIAL.clear();
        // Signal FIRST, then clear. A worker may be mid-store right now, and reset() only clears the
        // bookkeeping -- it does not reach the thread (mod_support #16).
        com.kishku7.chunksmith.lod.client.render.LodInjector.stop();
        com.kishku7.chunksmith.lod.client.render.LodInjector.reset();
    }

    /** The client's own store, keyed by server so two servers never mix: {@code chunksmith/lod/<server>}. */
    private static Path storeRoot() {
        final String key = host.isEmpty() ? "unknown" : host.replaceAll("[^a-zA-Z0-9._-]", "_");
        return ClientPlatform.gameDir().resolve("chunksmith").resolve("lod").resolve(key);
    }

    /**
     * Act on this client's OWN LOD settings, on behalf of a /cslod set typed at the server. The reply is
     * printed HERE rather than sent back for the server to print: the file being read and written is on
     * this machine, so the server cannot know the answer. Already on the client thread -- ClientPlatform
     * hands every payload to the client executor before calling handle() -- so Minecraft.getInstance() is
     * safe here.
     */
    private static void clientSetting(final CsLodMessages.ClientSetting request) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (request.action() == CsLodProtocol.SETTING_LIST) {
            say(player, Component.literal(
                    "[chunksmith] LOD client settings (config/" + CsLodClientConfig.FILE_NAME + "):"));
            for (final CsLodClientSettings.Setting setting : CsLodClientSettings.all()) {
                say(player, Component.literal(
                        "  " + setting.name() + " = " + setting.read() + "  -- " + setting.help()));
            }
            return;
        }

        final var found = CsLodClientSettings.find(request.name());
        if (found.isEmpty()) {
            say(player, Component.literal(
                    "[chunksmith] no LOD client setting called '" + request.name() + "'. Known: "
                            + String.join(", ", CsLodClientSettings.names())));
            return;
        }
        final CsLodClientSettings.Setting setting = found.get();

        if (request.action() == CsLodProtocol.SETTING_SHOW) {
            say(player, Component.literal(
                    "[chunksmith] " + setting.name() + " = " + setting.read() + "  -- " + setting.help()));
            return;
        }

        // SETTING_SET. A refused value is a SHAPE error -- a word where a number belongs. An out-of-range
        // value is accepted and CLAMPED, so the reply reports what was stored, not what was typed.
        if (!setting.write(request.value())) {
            final var expected = setting.kind().completions();
            say(player, Component.literal(
                    "[chunksmith] '" + request.value() + "' is not a valid value for " + setting.name()
                            + (expected.isEmpty() ? " (expected a whole number)"
                                    : " (expected one of: " + String.join(", ", expected) + ")")));
            return;
        }
        say(player, Component.literal(
                "[chunksmith] " + setting.name() + " = " + setting.read()
                        + " -- applied now and saved to config/" + CsLodClientConfig.FILE_NAME));
    }

    /**
     * Print one line into the local player's chat. The ONLY version-conditional code in this class, and it
     * is here rather than at each call site so there is one branch instead of six. MC 26 SPLIT
     * {@code Player.displayClientMessage(Component, boolean)} into {@code sendSystemMessage} /
     * {@code sendOverlayMessage}; the reasoning, the source citations and the two dodges that do NOT work
     * are in {@code compat.client_chat_statement}.
     */
    private static void say(final LocalPlayer player, final Component line) {
        //[[[cog
        // import cog, compat
        // cog.outl(compat.client_chat_statement(mcver, "player", "line"))
        //]]]
        //[[[end]]]
    }

    private static void send(final byte[] data) {
        ClientPlatform.sendToServer(data);
    }
}
