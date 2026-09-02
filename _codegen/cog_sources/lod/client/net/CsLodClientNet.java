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
import com.kishku7.chunksmith.lod.client.render.DhTarget;
import com.kishku7.chunksmith.lod.client.render.LodInjector;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;

/**
 * Client side of the Chunksmith LOD protocol.
 *
 * <p>The client drives the exchange: on join it says hello, announcing which renderers it has and the
 * radius its renderer draws (the server follows that number); the server answers with store availability,
 * the backchannel port and a token; the client asks for the region index, diffs it against the local
 * store, fetches only what it lacks, hands it to the renderer, and keeps doing so as the player travels
 * ({@link #travelTick}).
 *
 * <p>An empty store at join is not the end of the session. The old client asked once, was told
 * "nothing here" and stood down for good, which broke the ordinary case: an operator starting an
 * hours-long pregen with players already connected. We keep asking on a backed-off clock
 * ({@link CsLodRetry}), and a Chunksmith server re-sends its hello the moment its store becomes
 * servable. No relog either way.
 *
 * <p>Loader-blind: channel registration, sends, join/disconnect/tick and the game directory all go through
 * {@link com.kishku7.chunksmith.lod.client.ClientPlatform}, one source for Fabric and NeoForge. Every
 * fetch goes over the HTTP backchannel if the server advertised a port; in-band is for when there is no
 * port, or when the advertised one turns out to be unreachable (firewalled), which we discover by trying
 * it and getting nothing back.
 */
public final class CsLodClientNet {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /**
     * How far the player must travel before we ask what is now in range. Half a region: the server
     * indexes by region (512 blocks), so a shorter trigger cannot bring anything new into range, and a
     * much longer one lets the player outrun their own horizon.
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
     * What the server told us to fetch from, or empty when it named nothing. Kept apart from {@link
     * #host} rather than overwriting it, so a server that stops naming a host on a later hello falls
     * back to the connect address instead of keeping a stale override forever.
     */
    private static volatile String advertisedHost = "";

    /**
     * The dimension we are currently pulling for. always the one the player is actually in. This field held
     * {@code hello.dimensions().get(0)} in 3.1.0-beta-2 and never changed again, which is the failure
     * {@code CsLodDimension} documents. Now re-derived from the level ({@link #dimensionTick}); a dimension
     * change clears it and re-arms the exchange. Empty means "not pulling for anything".
     */
    private static volatile String activeDimension = "";

    /**
     * The dimension the player was in when we last looked. A difference from the level is the dimension
     * change; no reliable cross-loader, cross-version dimension-change event exists, and the level is
     * truth.
     */
    private static volatile String playerDimension = "";

    /** What the server told us it can serve. Re-read on every hello, so a later pregen shows up here. */
    private static volatile List<String> serverDimensions = List.of();

    /** The server answered and had nothing to give us -- yet. The state the old client could not leave. */
    private static volatile boolean awaitingStore;

    /** How long to wait before asking an empty-store server again. Backs off; reset on disconnect. */
    private static final CsLodRetry RETRY = new CsLodRetry();

    /**
     * What we told the server we can draw, cached from the first hello and re-used by every later one.
     * Not just an optimization: {@code Renderers.configuredRadiusBlocks()} reaches into voxy's config,
     * and the join handshake is the only place it is safe to do that (see {@link #hello}). A retry must
     * not go back and ask voxy again.
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
     * The entries of the last index the server gave us, the set the sync poll folds over (see
     * {@link #summary}). Deliberately the server's answer rather than a listing of our own store: the
     * server excludes regions its pregen is still writing, so folding our own directory would disagree on
     * every poll and pull a full index every interval for the entire length of a pregen.
     */
    private static volatile List<CsLodMessages.RegionEntry> lastIndex = List.of();

    /** When we last asked "has anything changed?". Reset by a real index; there is no point asking twice. */
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
    private static final Map<String, ByteArrayOutputStream> PARTIAL = new HashMap<>();

    private CsLodClientNet() {
    }

    public static void register() {
        ClientPlatform.registerClientNetworking(CsLodClientNet::handle);
        ClientPlatform.onJoin(CsLodClientNet::hello);
        ClientPlatform.onDisconnect(CsLodClientNet::reset);
        ClientPlatform.onClientTick(CsLodClientNet::travelTick);
    }

    /**
     * Asks again for what is in range, as the player travels. The server filters its index by the radius we
     * announced, measured from wherever the player is standing, so the same request sent from somewhere else
     * comes back with a different answer. The index is a few hundred bytes and the diff means we ask only for
     * what is genuinely new; standing still costs nothing.
     */
    private static void travelTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // Are we still in the dimension we think we are? Checked before anything else, and even while a
        // fetch is in flight. A fetch for the dimension the player has just left is what must be stopped.
        if (dimensionTick()) {
            return;
        }
        if (busy.get()) {
            return;
        }
        if (activeDimension.isEmpty()) {
            // Nothing to refresh yet, but if the server told us its store was empty (or had nothing for
            // the dimension we are in), keep asking; that may not be true any more.
            retryTick();
            silenceTick();
            return;
        }

        // The sync poll runs on its own clock, deliberately above every movement test below. Those only
        // fire once the player has travelled half a region, and a player who joins, walks to their base and
        // stays there used to get nothing more for the whole session. So we ask for two numbers (22 bytes
        // out, 34 back), never an index: an index every few minutes from every client rebuilds the memory bug.
        syncTick();

        long now = System.currentTimeMillis();
        if (now - lastIndexMillis < MIN_REFRESH_MILLIS) {
            return;
        }
        double dx = player.getX() - lastIndexX;
        double dz = player.getZ() - lastIndexZ;
        if (dx * dx + dz * dz < REFRESH_MOVE_BLOCKS * REFRESH_MOVE_BLOCKS) {
            return;
        }
        // A refresh is due. If the token is getting old, renew it first and let the server's answer drive
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
     * Checks whether the player just changed dimension, and re-arms the whole exchange for the level they are
     * now in.
     *
     * @return true if the dimension changed (the caller must do nothing else this tick)
     */
    private static boolean dimensionTick() {
        String now = CsLodDimension.current();
        if (now.isEmpty()) {
            // Mid-change: the old level is gone and the new one is not up. Wait for the next tick.
            return false;
        }
        if (now.equals(playerDimension)) {
            return false;
        }

        String from = playerDimension;
        playerDimension = now;

        // Whatever we were pulling was for the level the player has just left: an index or download that
        // lands now describes somewhere they no longer are, and the injector will refuse it. Cancel the
        // in-flight fetch so we are not holding the busy latch against the dimension we are about to ask for.
        activeDimension = "";
        inBandRoot = null;
        inBandDimension = "";
        inBandRegions = List.of();
        inBandManifest = null;
        // The last index described the dimension the player has left. Folding a sync answer against it
        // would compare the Nether's summary with the overworld's regions.
        lastIndex = List.of();
        lastSyncMillis = 0L;
        PARTIAL.clear();
        CsLodDownloader current = downloader;
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
            // First level of the session, so nothing has changed; we are just learning where we
            // started. hello() is already on its way; record it and let the normal handshake arm us.
            return true;
        }

        LOGGER.info("Chunksmith: the player moved from {} to {}. The LOD data for {} is a DIFFERENT world,"
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
     * content: one {@code openat} + ~3 {@code getdents64} + one {@code close} to list the 340 names, then
     * one {@code statx} per region actually in range (the name and radius tests both run before the stat).
     * mtime and size come out of that stat, and they are the freshness token now, against the whole-file
     * reads a 3.1.0-beta-3 index did on the server main thread; see {@code CsLodServerNet}.
     */
    private static void syncTick() {
        if (!CsLodClientConfig.isLoaded()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSyncMillis < CsLodClientConfig.syncIntervalMillis()) {
            return;
        }
        // Stamp before sending, or a slow answer means we re-ask on every tick until it lands.
        lastSyncMillis = now;
        try {
            send(CsLodMessages.requestSummary(activeDimension));
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: failed to ask the server for a LOD summary: {}", e.toString());
        }
    }

    /**
     * The server folded its in-range index to two numbers. Fold ours the same way and compare. Equal
     * means the server holds exactly the regions it last told us about, at exactly those versions, and we
     * hold every one: the 99% case, and it costs nothing. A difference means one of three things, and
     * all three have the same answer (pull the index and let the diff work it out): the server's store
     * grew, or we lost regions (deleted, truncated, a disk that lied), or a region we hold changed so its
     * recorded token no longer matches the advertised one. Runs off the game thread, a stat per region
     * of the last index, and the game thread never waits on a disk.
     */
    private static void summary(CsLodMessages.RegionSummary summary) {
        String dimension = summary.dimension();
        if (!dimension.equals(activeDimension)) {
            // The answer to a question we asked from a dimension we have since left. Drop it.
            return;
        }
        Path root = storeRoot();
        Path dir = CsLodStore.dimensionDir(root, dimension);
        if (dir == null) {
            LOGGER.warn("Chunksmith: server sent a malformed dimension id in a LOD summary; ignoring it");
            return;
        }
        List<CsLodMessages.RegionEntry> mine = lastIndex;

        Thread worker = new Thread(() -> {
            CsLodManifest manifest = CsLodManifest.open(root, dimension);
            if (manifest == null) {
                return;
            }
            CsLodSummary.Snapshot ours = manifest.fold(dir, mine);
            if (ours.count() == summary.count() && ours.aggregate() == summary.aggregate()) {
                LOGGER.debug("Chunksmith: LOD sync, nothing has changed ({} regions of {})",
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
     * We said hello and nothing came back. Say so once, at INFO. Two servers look identical from here and
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
        // Every word below is about the LOD terrain the player expected to see. With no renderer our hello
        // went out to reach /cslod set rather than to ask for data (see hello()), so silence is not that
        // failure.
        if (!capsVoxy && !capsDh) {
            return;
        }
        if (System.currentTimeMillis() - helloSentMillis < HELLO_TIMEOUT_MILLIS) {
            return;
        }
        silenceReported = true;
        LOGGER.info("Chunksmith: no LOD data is being offered by this server (it did not answer our hello"
                        + " within {}s). Either it does not run Chunksmith (which is normal, and nothing is"
                        + " wrong) or it runs a version older than 3.1.0-beta-4, which speaks an LOD"
                        + " protocol older than v{} and cannot serve this client. If you expected LOD terrain"
                        + " here, the server and every client must be on 3.1.0-beta-4 or later.",
                HELLO_TIMEOUT_MILLIS / 1000L, CsLodProtocol.VERSION);
    }

    /**
     * The server had nothing for us. Ask again on a backing-off clock: one ~10-byte packet at 15s, 30s,
     * 60s, then every two minutes, so joining before the pregen finishes does not cost the session.
     */
    private static void retryTick() {
        if (!awaitingStore) {
            return;
        }
            // Singleplayer. The world's own injector already draws these LODs directly, so this path would
            // be a duplicate, and a duplicate is the last thing that should be put on a timer.
        if (host.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!RETRY.due(now)) {
            return;
        }
        RETRY.attempted(now);
        // Loud for the first couple of minutes so the log shows we are still trying; quiet after that.
        String line = "Chunksmith: the server still has no LOD data (asked " + RETRY.attempts()
                + " more time(s)); asking again in " + RETRY.delayMillis() / 1000L + "s";
        if (RETRY.attempts() <= 3) {
            LOGGER.info(line);
        } else {
            LOGGER.debug(line);
        }
        sendHello(false);
    }

    /**
     * Tells the server what we can render, and how far. The join handshake.
     *
     * <p>We say hello even with no renderer installed (3.4.0). {@code /cslod set} is a server command that
     * only relays to a client the server has heard from ({@code CsLodServerNet.hasLodClient}), so
     * returning here silently made the two settings in {@code config/chunksmith-lod.properties}
     * unreachable in-game for exactly the players most likely to be fiddling with them. The hello carries
     * {@code hasVoxy}/{@code hasDh} both false and the server answers with an empty hello and serves no
     * data. Loading the config here is load-bearing: {@link CsLodClientConfig} only learns where its file
     * lives from this call, and until it does a {@code /cslod set} would change the value in memory and
     * write no file at all.
     */
    private static void hello() {
        boolean voxy = Renderers.hasVoxy();
        boolean dh = Renderers.hasDh();
        Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() != null) {
            host = client.getCurrentServer().ip;
            int colon = host.lastIndexOf(':');
            if (colon > 0) {
                host = host.substring(0, colon);
            }
        }
        // Read the renderers' radius at this point and nowhere earlier: this is the first moment both
        // renderers are up, and asking voxy sooner (e.g. from the init status line) class-loads its config
        // before voxy initializes and leaves voxy silently inert for the whole session. See VoxyRadius.
        // Cache it; every later hello re-uses these numbers rather than going back to voxy's config.
        capsVoxy = voxy;
        capsDh = dh;
        capsRadius = Renderers.configuredRadiusBlocks();
        // The sync interval, with its floor applied in code (see CsLodClientConfig: "sync-interval-
        // seconds=1" must not become a poll storm against a server trying to run a pregen).
        LOGGER.info("Chunksmith: {}", CsLodClientConfig.load(ClientPlatform.gameDir().resolve("config")));
        if (!voxy && !dh) {
            LOGGER.info("Chunksmith: hello, no LOD renderer installed (voxy / Distant Horizons), so no"
                    + " LOD data will be requested or drawn. Saying hello anyway so that /cslod set can"
                    + " reach this client's settings.");
        } else {
            LOGGER.info("Chunksmith: saying hello with voxy={} dh={} radius={} blocks", voxy, dh, capsRadius);
        }
        // Name the DH the player actually has, at join: we compile against the standalone
        // distanthorizonsapi artifact and support a wide range of DH releases. DhTarget hard-references DH
        // types, so only touch it when DH is really present.
        if (dh) {
            LOGGER.info("Chunksmith: feeding {}", DhTarget.version());
        }
        sendHello(true);
    }

    /**
     * Puts a hello on the wire. Three callers (the join handshake, an empty-store retry and a token renewal),
     * and the server answers every one identically with its current hello, which is why none of this needed a
     * new packet id. An older server answers a repeat hello exactly as it answered the first.
     */
    private static void sendHello(boolean first) {
        try {
            send(CsLodMessages.encode(new CsLodMessages.ClientHello(
                    CsLodProtocol.VERSION, capsVoxy, capsDh, capsRadius)));
            if (first) {
                RETRY.started(System.currentTimeMillis());
                helloSentMillis = System.currentTimeMillis();
            }
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: failed to say hello: " + e);
        }
    }

    private static void handle(byte[] data) {
        if (data.length == 0) {
            return;
        }
        try (DataInputStream in = CsLodMessages.reader(data)) {
            byte id = in.readByte();
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
                    CsLodManifest manifest = inBandManifest;
                    if (manifest != null) {
                        try {
                            manifest.save();
                        } catch (IOException e) {
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
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: malformed LOD message: " + e);
        }
    }

    private static void serverHello(CsLodMessages.ServerHello hello) {
        helloAnswered = true;
        if (hello.protocolVersion() != CsLodProtocol.VERSION) {
            // A 3.1.0-beta-3 server speaks v1, where the hash field is a CRC32 of the region's contents --
            // which is what forced that server to read every file in our radius (the bug). The two
            // protocols cannot interoperate; say so in words a player can act on, once.
            LOGGER.warn("Chunksmith: this server speaks LOD protocol v{} and we speak v{}. Not fetching."
                            + " The server and the client must be on the same Chunksmith version"
                            + " (v1 is 3.1.0-beta-3 and earlier; v2 is 3.1.0-beta-4 and later).",
                    hello.protocolVersion(), CsLodProtocol.VERSION);
            return;
        }
        if (!capsVoxy && !capsDh) {
            // No renderer. Our hello was an introduction so /cslod set can reach us, and it has been
            // answered. Do not arm a dimension, request an index, or enter the empty-store retry loop
            // against a server that is quite right not to send us anything.
            LOGGER.debug("Chunksmith: the server answered our hello; with no renderer installed nothing"
                    + " will be fetched, but /cslod set can now reach this client");
            return;
        }
        if (!hello.storeAvailable() || hello.dimensions().isEmpty()) {
            // Not the end of the session: the operator has almost certainly not run the pregen yet, and
            // when they do we want the player to see it without being told to relog. (In singleplayer the
            // world's own injector covers this state, so retryTick stays out of it.)
            if (!awaitingStore) {
                awaitingStore = true;
                LOGGER.info("Chunksmith: the server has no pregenerated LOD data yet."
                        + " Staying connected and checking again (every {}s at first, then every {}s)"
                        + "; it will arrive on its own if the operator pregenerates, and you do NOT"
                        + " need to relog.",
                        CsLodRetry.FIRST_DELAY_MILLIS / 1000L, CsLodRetry.MAX_DELAY_MILLIS / 1000L);
            }
            return;
        }
        token = hello.token();
        tokenIssuedMillis = System.currentTimeMillis();
        backchannelPort = hello.backchannelPort();
        // A server that names a host overrides the address we connected to. Almost no server does,
        // and the connect address is the better answer when none is named: it demonstrably reaches
        // this server from where this player is sitting. But it is wrong wherever the backchannel
        // lives at a different address from the game port -- a proxy in front, or a host that maps
        // extra ports onto another IP -- and until 3.16.0 there was no way for the server to say so
        // (mod_support #24). Empty from every 3.15.0 server and from every server that has not set
        // the key, which is why this reads as "override if named" rather than as a new requirement.
        advertisedHost = hello.advertisedHost() == null ? "" : hello.advertisedHost();
        serverDimensions = hello.dimensions();

        // The dimension is the one the player is standing in, NEVER the first one the server listed.
        // That single line was the 3.1.0-beta-2 bug; see the note on activeDimension.
        String mine = CsLodDimension.current();
        if (mine.isEmpty()) {
            // No level yet; still loading in. dimensionTick() re-hellos once there is a level to name.
            return;
        }
        playerDimension = mine;

        if (!serverDimensions.contains(mine)) {
        // The server has data, just not for the dimension we are in. Normal enough (most operators pregen
        // only the overworld), and not a reason to render another dimension's terrain here.
            if (!awaitingStore) {
                awaitingStore = true;
                LOGGER.info("Chunksmith: the server has LOD data for {}, but nothing for {}, the"
                                + " dimension you are in. Nothing will be drawn here (data from another"
                                + " dimension is NOT a substitute). Checking again as you play.",
                        serverDimensions, mine);
            }
            activeDimension = "";
            return;
        }

        if (awaitingStore) {
            LOGGER.info("Chunksmith: the server NOW has LOD data for {}. Fetching it (after {} check(s),"
                            + " with no relog)", mine, RETRY.attempts());
            awaitingStore = false;
            RETRY.reset();
        }

        // Announce only on the hello that arms us for this dimension. The later ones are token renewals,
        // and a renewal every few minutes of travel must not re-narrate the connection.
        boolean arming = activeDimension.isEmpty();
        if (backchannelPort == 0) {
            // The operator has not opened the port. Not an error: we ask in-band instead, which is much
            // slower (it rides the gameplay connection) but works everywhere.
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

    /**
     * Where to fetch regions from: the host the server named, or failing that the address we
     * connected to. One place, so the reachability probe, the download and the log line can never
     * disagree about which of the two they meant.
     */
    private static String fetchHost() {
        String named = advertisedHost;
        return named.isEmpty() ? host : named;
    }

    /** Asks what is in range right now, and remembers where we asked from. */
    private static void requestIndex(String dimension) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastIndexX = player.getX();
            lastIndexZ = player.getZ();
        }
        lastIndexMillis = System.currentTimeMillis();
        try {
            send(CsLodMessages.requestIndex(dimension));
        } catch (IOException e) {
            busy.set(false);
            LOGGER.warn("Chunksmith: failed to request the region index: {}", e.toString());
        }
    }

    private static void index(CsLodMessages.RegionIndex index) {
        Path root = storeRoot();
        // The dimension is server-supplied and becomes a filesystem path in every transport below. Gate it
        // once at the top too, and free the busy latch we took to get here.
        if (CsLodStore.dimensionDir(root, index.dimension()) == null) {
            LOGGER.warn("Chunksmith: server sent a malformed dimension id; ignoring the region index");
            busy.set(false);
            return;
        }
        // Keep it. This is the set the sync poll folds against (see summary()), and it carries each
        // region's freshness token to the injector. A region whose token has moved must be re-injected,
        // not skipped as "already drawn". Bare coordinates, as we used to carry, would throw those away.
        lastIndex = index.regions();
        lastSyncMillis = System.currentTimeMillis();

        String fetchHost = fetchHost();
        if (backchannelPort == 0 || token.isEmpty() || fetchHost.isEmpty()) {
            inBand(index, root);
            return;
        }

        downloader = new CsLodDownloader(root);

        // Off the game thread: a download must never make the game stutter. Injection follows on the same thread.
        Thread worker = new Thread(() -> {
            try {
                // One cheap probe before we queue anything: an advertised-but-unreachable port costs a
                // full connect timeout per region, ~30s of dead air on a 9-region store before the
                // fallback fires, and it scales with the store. A single socket answers the same question
                // in 2s.
                if (!reachable(fetchHost, backchannelPort)) {
                    LOGGER.warn("Chunksmith: the backchannel on port {} is advertised but unreachable;"
                            + " falling back to the in-band channel (slower)", backchannelPort);
                    backchannelPort = 0;
                    Minecraft.getInstance().execute(() -> inBand(index, root));
                    return;
                }

                CsLodDownloader current = downloader;
                current.download(fetchHost, backchannelPort, token, index,
                        line -> LOGGER.info("Chunksmith: {}", line));

                // Backstop: the port answered a socket but every fetch still failed (a proxy that accepts
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

                // Almost every player is on this path. It injects inline here rather than through
                // injectAsync (we are already off the game thread, and the download has to finish first),
                // and that difference is what made the 3.3.0 stop-flag bug invisible: the mod_support #16
                // fix added an arm() to injectAsync, the in-band fallback, and this call site never got
                // one. A third injection call site must go through injectRegions and nowhere else.
                LodInjector.injectRegions(
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
    private static boolean reachable(String address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 2_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The slow path: ask for the regions down the game connection. Used when the server never opened a
     * backchannel port, or advertised one we cannot reach. Asks only for what we are actually missing,
     * exactly as the fast path does; the cache rule does not change just because the transport did.
     */
    private static void inBand(CsLodMessages.RegionIndex index, Path root) {
        inBandRoot = root;
        inBandDimension = index.dimension();
        inBandRegions = index.regions();

        // The manifest is the cache check on both transports, and it is where each region's freshness
        // token is recorded as the slices land, so the next index can tell what we hold.
        CsLodManifest manifest = CsLodManifest.open(root, index.dimension());
        inBandManifest = manifest;

        List<CsLodMessages.RegionEntry> wanted = new ArrayList<>();
        for (CsLodMessages.RegionEntry entry : index.regions()) {
            if (!CsLodCache.have(root, index.dimension(), manifest, entry)) {
                wanted.add(entry);
            }
        }
        LOGGER.info("Chunksmith: in-band fetch. {} regions within my radius, {} already cached,"
                        + " {} to fetch (this is the slow path)",
                index.regions().size(), index.regions().size() - wanted.size(), wanted.size());
        if (wanted.isEmpty()) {
            injectAsync(root, index.dimension(), index.regions());
            return;
        }
        try {
            send(CsLodMessages.requestRegions(index.dimension(), wanted));
        } catch (IOException e) {
            busy.set(false);
            LOGGER.warn("Chunksmith: failed to request in-band regions: {}", e.toString());
        }
    }

    /**
     * Reassembles an in-band region file, slice by slice. Written to a .part file and moved into place only
     * when the last slice lands, so a transfer cut off half way can never be mistaken for a cached region on
     * the next join.
     */
    private static void slice(CsLodMessages.RegionSlice slice) {
        Path root = inBandRoot;
        if (root == null) {
            return;
        }
        String key = slice.regionX() + "." + slice.regionZ();
        ByteArrayOutputStream buffer =
                PARTIAL.computeIfAbsent(key, ignored -> new ByteArrayOutputStream());
        buffer.writeBytes(slice.data());

        if (!slice.last()) {
            return;
        }
        PARTIAL.remove(key);
        try {
            // slice.dimension() is a distinct wire value, so gate it here too (D20: every consumer validates).
            Path dimDir = CsLodStore.dimensionDir(root, slice.dimension());
            if (dimDir == null) {
                LOGGER.warn("Chunksmith: dropping an in-band slice with a malformed dimension id");
                return;
            }
            Path target = dimDir.resolve("r." + slice.regionX() + "." + slice.regionZ() + ".cslod");
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(temp, buffer.toByteArray());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            // Record what the server said about the region just assembled. The in-band request echoes
            // coordinates only, so the token has to come from the index that prompted the fetch.
            CsLodManifest manifest = inBandManifest;
            CsLodMessages.RegionEntry advertised = advertised(slice.regionX(), slice.regionZ());
            if (manifest != null && advertised != null) {
                manifest.put(slice.regionX(), slice.regionZ(), advertised.hash(), Files.size(target));
            }
        } catch (IOException e) {
            LOGGER.warn("Chunksmith: failed to store in-band region {}: {}", key, e.toString());
        }
    }

    /** Returns what the server told us about this region in the index that prompted the in-band fetch. */
    private static CsLodMessages.RegionEntry advertised(int regionX, int regionZ) {
        for (CsLodMessages.RegionEntry entry : inBandRegions) {
            if (entry.regionX() == regionX && entry.regionZ() == regionZ) {
                return entry;
            }
        }
        return null;
    }

    /** Hands the new regions to the renderers, off the game thread. */
    private static void injectAsync(final Path root, final String dimension,
                                    final List<CsLodMessages.RegionEntry> regions) {
        // Nothing to arm: the injector reads the current session generation when it starts (LodInjector.SESSION).
        Thread worker = new Thread(() -> {
            try {
                LodInjector.injectRegions(root, dimension, regions,
                        line -> LOGGER.info("Chunksmith: {}", line));
            } finally {
                busy.set(false);
            }
        }, "chunksmith-lod-inject");
        worker.setDaemon(true);
        worker.start();
    }

    public static void cancel() {
        CsLodDownloader current = downloader;
        if (current != null) {
            current.cancel();
            send(CsLodMessages.cancel());
        }
    }

    public static String describe() {
        CsLodDownloader current = downloader;
        return current == null ? "idle" : current.describe();
    }

    private static void reset() {
        cancel();
        downloader = null;
        token = "";
        tokenIssuedMillis = 0L;
        backchannelPort = 0;
        host = "";
        advertisedHost = "";
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
        // Signal first, then clear. A worker may be mid-store right now, and reset() only clears the
        // bookkeeping; it does not reach the thread (mod_support #16).
        LodInjector.stop();
        LodInjector.reset();
    }

    /** The client's own store, keyed by server so two servers never mix: {@code chunksmith/lod/<server>}. */
    private static Path storeRoot() {
        String key = host.isEmpty() ? "unknown" : host.replaceAll("[^a-zA-Z0-9._-]", "_");
        return ClientPlatform.gameDir().resolve("chunksmith").resolve("lod").resolve(key);
    }

    /**
     * Act on this client's own LOD settings, on behalf of a /cslod set typed at the server. The reply is
     * printed on this side rather than sent back for the server to print: the file being read and written
     * is on this machine, so the server cannot know the answer. Already on the client thread --
     * ClientPlatform hands every payload to the client executor before calling handle(), so
     * Minecraft.getInstance() is safe here.
     */
    private static void clientSetting(CsLodMessages.ClientSetting request) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (request.action() == CsLodProtocol.SETTING_LIST) {
            say(player, Component.literal(
                    "[chunksmith] LOD client settings (config/" + CsLodClientConfig.FILE_NAME + "):"));
            for (CsLodClientSettings.Setting setting : CsLodClientSettings.all()) {
                say(player, Component.literal(
                        "  " + setting.name() + " = " + setting.read() + "  -- " + setting.help()));
            }
            return;
        }

        var found = CsLodClientSettings.find(request.name());
        if (found.isEmpty()) {
            say(player, Component.literal(
                    "[chunksmith] no LOD client setting called '" + request.name() + "'. Known: "
                            + String.join(", ", CsLodClientSettings.names())));
            return;
        }
        CsLodClientSettings.Setting setting = found.get();

        if (request.action() == CsLodProtocol.SETTING_SHOW) {
            say(player, Component.literal(
                    "[chunksmith] " + setting.name() + " = " + setting.read() + "  -- " + setting.help()));
            return;
        }

        // SETTING_SET. A refused value is a shape error: a word where a number belongs. An out-of-range
        // value is accepted and clamped, so the reply reports what was stored, not what was typed.
        if (!setting.write(request.value())) {
            var expected = setting.kind().completions();
            say(player, Component.literal(
                    "[chunksmith] '" + request.value() + "' is not a valid value for " + setting.name()
                            + (expected.isEmpty() ? " (expected a whole number)"
                                    : " (expected one of: " + String.join(", ", expected) + ")")));
            return;
        }
        say(player, Component.literal(
                "[chunksmith] " + setting.name() + " = " + setting.read()
                        + ", applied now and saved to config/" + CsLodClientConfig.FILE_NAME));
    }

    /**
     * Prints one line into the local player's chat. This is the only version-conditional code in the class,
     * and it lives here rather than at each call site so there is one branch instead of six. MC 26 split
     * {@code Player.displayClientMessage(Component, boolean)} into {@code sendSystemMessage} /
     * {@code sendOverlayMessage}; the reasoning, the source citations and the two dodges that do not work are
     * in {@code compat.client_chat_statement}.
     */
    private static void say(LocalPlayer player, Component line) {
        //[[[cog
        // import cog, compat
        // cog.outl(compat.client_chat_statement(mcver, "player", "line"))
        //]]]
        //[[[end]]]
    }

    private static void send(byte[] data) {
        ClientPlatform.sendToServer(data);
    }
}
