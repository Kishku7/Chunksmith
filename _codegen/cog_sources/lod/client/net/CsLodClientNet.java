package com.kishku7.chunksmith.lod.client.net;

import com.kishku7.chunksmith.lod.client.CsLodCache;
import com.kishku7.chunksmith.lod.client.CsLodClientConfig;
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
 * <p>The client drives the whole exchange:
 * <ol>
 *   <li>on join, say hello -- announcing WHICH RENDERERS we actually have, and the radius our renderer is
 *       configured to draw (the server follows that number rather than guessing);</li>
 *   <li>the server answers with the store's availability, the backchannel port, and a token;</li>
 *   <li>ask for the region index, diff it against our local store, and fetch only what we lack;</li>
 *   <li>hand the new regions to whichever renderer is installed;</li>
 *   <li><b>keep doing 3-4 as the player travels</b> -- see {@link #travelTick}.</li>
 * </ol>
 *
 * <p><b>An empty store at join is not the end of the session.</b> It used to be: the client asked once, got
 * "nothing here", logged a line and stood down -- {@code activeDimension} was never set, so even the travel
 * loop never armed, and the player got nothing for the rest of the session however long they played. But an
 * empty store at join is the NORMAL case, not an edge one: operators start hours-long pregens with players
 * already connected, and the store fills up behind them. So we do not stand down. We arm a backed-off retry
 * clock ({@link CsLodRetry}) and keep asking, and a Chunksmith server ALSO tells us, unprompted, the moment
 * its store becomes servable -- it re-sends its hello, which is the same message we already handle. Either
 * way the data arrives and the travel loop arms itself, with no relog.
 *
 * <p>Nothing happens on a server that is not running Chunksmith: it will not answer this channel, and we
 * simply stay quiet. Nothing happens if the player has no LOD renderer either -- the server refuses to
 * send, which is the right call: there is no point spending a server's bandwidth on data nobody can draw.
 *
 * <p>Loader-blind: every loader difference this needs -- registering the play channel, sending on it, the
 * join/disconnect/tick events, and the game directory -- goes through
 * {@link com.kishku7.chunksmith.lod.client.ClientPlatform}. This class is the SAME source on Fabric and
 * NeoForge.
 *
 * <p><b>Transport: the fast path ALWAYS wins when it is there.</b> Every fetch -- the one on join and every
 * one after it -- goes over the HTTP backchannel if the server advertised a port. The in-band channel is
 * used only when there is no port, or when the port turns out to be unreachable (advertised but firewalled),
 * which we discover the only way you can: by trying it and getting nothing back.
 */
public final class CsLodClientNet {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /**
     * How far the player must travel before we ask the server what is now in range.
     *
     * <p>Half a region. The server indexes by REGION (512 blocks), so a shorter trigger cannot bring
     * anything new into range and would just re-ask for the same list; a much longer one lets the player
     * outrun their own horizon.
     */
    private static final double REFRESH_MOVE_BLOCKS = 256.0;

    /** Never re-ask faster than this, however fast the player is moving (elytra, /tp, boats on ice). */
    private static final long MIN_REFRESH_MILLIS = 5_000L;

    /**
     * Re-handshake before the backchannel token can go stale.
     *
     * <p>A token lives ten minutes. A session lives hours. The join token is fine for the join fetch, but a
     * travel refresh an hour later would present an expired one, every fetch would 403, and the client would
     * silently drop to the in-band fallback for the rest of the session. So when a refresh is due and the
     * token is getting on, we say hello again first: the server answers with a fresh one and the refresh
     * rides on from there. Three quarters of the lifetime, so it is always replaced well before it dies.
     */
    private static final long TOKEN_REFRESH_MILLIS = CsLodProtocol.TOKEN_TTL_MILLIS / 4L * 3L;

    private static volatile CsLodDownloader downloader;
    private static volatile String token = "";
    private static volatile long tokenIssuedMillis;
    private static volatile int backchannelPort;
    private static volatile String host = "";

    /**
     * The dimension we are currently pulling for. ALWAYS the one the player is actually in.
     *
     * <p><b>This was the bug in 3.1.0-beta-2, and it is worth naming precisely.</b> It used to be set to
     * {@code hello.dimensions().get(0)} -- the FIRST dimension the server happened to list, which on every
     * normal server is the overworld -- and then never changed for the rest of the session. Walk through a
     * Nether portal and the client kept asking for the OVERWORLD's region index, kept reading the
     * OVERWORLD's store directory, and handed those records to the injector, which pushed them into the
     * level the player was now in. The player stood in the Nether looking at grass, oceans and beaches
     * hanging in the sky, while every counter and every log line reported success.
     *
     * <p>So it is no longer a remembered answer. It is re-derived from the LEVEL (see
     * {@link #dimensionTick}), and the moment the player changes dimension it is cleared and the whole
     * exchange is re-armed for the level they are actually standing in. Empty means "not pulling for
     * anything" -- either we have not been armed yet, or the server has nothing for the dimension we are in.
     */
    private static volatile String activeDimension = "";

    /**
     * The dimension the player was in when we last looked. Compared against the level every tick; a
     * difference IS the dimension change (there is no reliable cross-loader, cross-version dimension-change
     * event, and there does not need to be -- the level is the truth).
     */
    private static volatile String playerDimension = "";

    /** What the server told us it can serve. Re-read on every hello, so a later pregen shows up here. */
    private static volatile List<String> serverDimensions = List.of();

    /**
     * The server answered, and had nothing to give us -- yet.
     *
     * <p>This is the state the old client had no name for, and so could not leave.
     */
    private static volatile boolean awaitingStore;

    /** How long to wait before asking an empty-store server again. Backs off; reset on disconnect. */
    private static final CsLodRetry RETRY = new CsLodRetry();

    /**
     * What we told the server we can draw. Cached from the FIRST hello and re-used by every later one.
     *
     * <p>Not just an optimization: {@code Renderers.configuredRadiusBlocks()} reaches into voxy's config,
     * and the one place it is safe to do that is the join handshake (see the note in {@link #hello}). A
     * retry must not go back and ask voxy again.
     */
    private static volatile boolean capsVoxy;
    private static volatile boolean capsDh;
    private static volatile int capsRadius;

    /** One fetch at a time. A travel refresh must never race the join fetch, or itself. */
    private static final AtomicBoolean busy = new AtomicBoolean();

    /**
     * How long to wait for an answer to our hello before saying, once, that none came.
     *
     * <p>Silence has three causes and they are indistinguishable on the wire: the server does not run
     * Chunksmith at all (the common one, and the one we must stay quiet about); the server runs a Chunksmith
     * whose LOD protocol is not ours (v1 -- 3.1.0-beta-3 and earlier -- which logs "not serving" on ITS side
     * and sends nothing back); or the packet was lost. We cannot tell them apart, so we do not pretend to --
     * but a player whose LOD silently never arrives deserves the one sentence that lets them work it out,
     * and it belongs at DEBUG so a normal vanilla server does not get a scary line for behaving normally.
     */
    private static final long HELLO_TIMEOUT_MILLIS = 10_000L;

    /**
     * The entries of the LAST index the server gave us, and the dimension they describe.
     *
     * <p>This is the set the periodic sync folds over -- see {@link #summary}. It is deliberately the
     * SERVER's last answer rather than a listing of our own store: the server excludes regions its pregen is
     * still writing, and folding over our own directory instead would mean counting our stale copies of
     * those forever, disagreeing with the server on every poll, and pulling a full index every interval for
     * the entire length of a pregen. Both sides fold the same set, so "nothing changed" compares equal.
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
     * Ask again for what is in range, as the player travels.
     *
     * <p>The server's index is filtered by the radius the client announced, measured from the player's
     * CURRENT position -- so the same request, sent from somewhere else, returns a different answer. Walk
     * toward terrain the server pregenerated and never sent you, and this is what fetches it.
     *
     * <p>Cheap by construction: the index is a few hundred bytes, the diff against the local store means we
     * ask for only what is genuinely new, and {@link com.kishku7.chunksmith.lod.client.render.LodInjector} injects
     * each region exactly once per session. Standing still costs nothing at all.
     */
    private static void travelTick() {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // BEFORE anything else: are we even still in the dimension we think we are? A portal changes the
        // answer to every question below it -- which store to read, which index to ask for, and which level
        // the records belong in. Checked on every tick, and checked even while a fetch is in flight, because
        // a fetch in flight for the dimension the player has just LEFT is exactly what must be stopped.
        if (dimensionTick()) {
            return;
        }
        if (busy.get()) {
            return;
        }
        if (activeDimension.isEmpty()) {
            // Nothing to refresh yet -- but that no longer means nothing to do. If the server told us its
            // store was empty (or had nothing for the dimension we are standing in), keep asking; that may
            // not be true any more.
            retryTick();
            silenceTick();
            return;
        }

        // THE SYNC POLL, AND IT RUNS ON ITS OWN CLOCK -- deliberately above every movement test below.
        //
        // Everything under this line is about a player who is MOVING: it fires when they have travelled half
        // a region, and does nothing at all when they have not. That is the gap. A player who joins, walks to
        // their base and stays there receives whatever had settled at that moment and NOTHING for the rest of
        // the session, however many hours of pregen run behind them -- because the only thing that ever
        // asked the server a second question was movement. They have to relog, and nobody tells them that.
        //
        // So we ask anyway. Not for an index -- an index is the expensive thing, and asking for one every few
        // minutes from every client is how the memory bug gets rebuilt with better manners. We ask for two
        // numbers (22 bytes out, 34 back) and pay for an index only when those two numbers say something
        // actually changed. See CsLodSummary.
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
        // A refresh is due. If our token is getting old, renew it FIRST -- say hello again, and let the
        // server's answer drive this refresh. Stamp the clock before we send, or we would re-send on every
        // tick until the answer lands.
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
            // Mid-change: the old level is gone and the new one is not up. Not a dimension, and not a
            // reason to forget the one we are in -- just wait for the next tick.
            return false;
        }
        if (now.equals(playerDimension)) {
            return false;
        }

        final String from = playerDimension;
        playerDimension = now;

        // Whatever we were pulling was for the level the player has just LEFT. Drop it on the floor: an
        // index or a download that lands after this point describes somewhere the player no longer is, and
        // the injector will refuse it (it checks the level's own dimension). Cancel the in-flight fetch so
        // we are not holding the busy latch against the dimension we are about to ask for.
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
            // Tell the server to stop too. Both transports: an in-band drip-feed has no downloader to
            // cancel, and left running it would keep spending the gameplay connection on a dimension the
            // player has walked out of.
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
            // First level of the session. hello() is already on its way (or has been answered) -- this is
            // not a CHANGE, it is us learning where we started. Do not re-hello; just record it and let the
            // normal handshake arm us.
            return true;
        }

        LOGGER.info("Chunksmith: the player moved from {} to {} -- the LOD data for {} is a DIFFERENT world,"
                + " so asking the server what it has for {}", from, now, from, now);

        // Ask again. The hello is the same message the server answers on join, so this needs no new packet
        // and works against any Chunksmith server: it comes back with the dimensions it can serve and a
        // fresh token, and serverHello() arms us for the dimension we are NOW in.
        awaitingStore = false;
        RETRY.reset();
        sendHello(true);
        return true;
    }

    /**
     * "Has anything changed?" -- once per configured interval, whatever the player is doing.
     *
     * <p>The cost of one poll, for the store that started all this (340 regions, 1567 MB, a 4-region radius,
     * 81 regions in range):
     * <ul>
     *   <li><b>Client -> server: 22 bytes.</b> An id and {@code "minecraft_overworld"}.</li>
     *   <li><b>Server -> client: 34 bytes.</b> An id, the dimension, a count and an aggregate.</li>
     *   <li><b>Server-side work: ~86 syscalls, on a background thread, and ZERO bytes of file content.</b>
     *       One {@code openat} + ~3 {@code getdents64} + one {@code close} to list the 340 names, then one
     *       {@code statx} for each of the 81 regions actually in range -- the name test and the radius test
     *       both run before the stat, so the other 259 are never touched. mtime and size come out of that one
     *       stat, and mtime and size ARE the freshness token now.</li>
     *   <li><b>Client-side work:</b> one {@code size()} stat per region of the last index -- 81 -- folded
     *       against a manifest that is already in memory. Off the game thread.</li>
     * </ul>
     *
     * <p>Compare what one INDEX cost in 3.1.0-beta-3: 366.9 MB read into the heap, every buffer G1-humongous,
     * on the server main thread. A 30-second poll from a hundred clients is now cheaper than a single one of
     * those was.
     *
     * <p>The poll is skipped while a fetch is in flight (the answer would only be acted on by taking a latch
     * we do not hold) and while we have no dimension armed (there is no index to compare against -- that case
     * belongs to {@link #retryTick}, which is asking a different question).
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
     * The server folded its in-range index to two numbers. Fold OURS the same way and compare.
     *
     * <p>Equal means: the server holds exactly the regions it last told us about, at exactly the versions it
     * last told us about, and we hold every one of them. Nothing to do -- and this is the case that must be
     * free, because it is the case 99% of the time.
     *
     * <p>Different means one of three things, and we do not need to know which, because the answer to all
     * three is the same: pull the index and let the existing diff work out what to fetch.
     * <ol>
     *   <li>the server's store GREW (a pregen settled new regions, or grew existing ones) -- the count or the
     *       aggregate moves;</li>
     *   <li>WE lost regions (deleted, truncated, a disk that lied) -- our fold drops them, so our count falls
     *       below the server's;</li>
     *   <li>a region we hold CHANGED -- our recorded token no longer matches the advertised one, so it stops
     *       contributing and both numbers move.</li>
     * </ol>
     *
     * <p>Runs off the game thread: it is a stat per region of the last index, and the game thread does not
     * wait for a disk for anything, ever.
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
                // Re-check on the game thread: the player may have walked through a portal while we were
                // statting, and requestIndex must never be armed for a dimension they have left.
                if (dimension.equals(activeDimension)) {
                    requestIndex(dimension);
                }
            });
        }, "chunksmith-lod-sync");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * We said hello and nothing came back. Say so ONCE, at INFO.
     *
     * <p>Two different servers look exactly like this from here, and we cannot tell them apart -- because
     * the thing that would tell us apart is an answer, and neither one sends one:
     *
     * <ol>
     *   <li>a server that does not run Chunksmith at all. Normal. Nothing is wrong.</li>
     *   <li>a server running 3.1.0-beta-3 or earlier: it sees our v2 hello, refuses it as a protocol it does
     *       not know, and replies with nothing. (Our OWN v2 server deliberately answers an old client's v1
     *       hello for exactly this reason -- so it can name the mismatch. An old server does us no such
     *       favour.)</li>
     * </ol>
     *
     * <p>This was DEBUG, on the reasoning that case 1 is common and a scary line on every vanilla server is
     * noise. That trade is wrong: case 2 is a player who updated their client before the server updated,
     * staring at an empty horizon with not one word of explanation anywhere in their log -- a silent
     * failure, which is the one thing this mod refuses to ship. So it is INFO, and it is worded to be TRUE
     * of both cases: it states what is (no LOD data is on offer), names both causes, and does not guess
     * which one it is looking at. The price is one INFO line, once per connection, on any server without
     * Chunksmith. That is a fair price for never leaving someone in the dark.
     *
     * <p>(The precise alternative -- ask the platform whether the server registered our channel, so only the
     * genuine old-server case speaks -- needs a new client capability on all three loaders. It is a better
     * message, not a more honest one, and it is not worth a new cross-loader surface at a publish gate.)
     */
    private static void silenceTick() {
        if (helloAnswered || silenceReported || helloSentMillis == 0L || host.isEmpty()) {
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
     * The server had nothing for us. Ask it again, on a backing-off clock.
     *
     * <p>Costs one ~10-byte packet at 15s, 30s, 60s, then every two minutes for as long as the player stays.
     * That is the entire price of never again losing a session to "you joined before the pregen".
     */
    private static void retryTick() {
        if (!awaitingStore) {
            return;
        }
        // Singleplayer. The world's own injector already draws these LODs directly -- this multiplayer path
        // runs against the integrated server too, harmlessly, but it is a DUPLICATE, and a duplicate is the
        // last thing that should be put on a timer.
        if (host.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!RETRY.due(now)) {
            return;
        }
        RETRY.attempted(now);
        // Loud for the first couple of minutes, so anyone reading the log can SEE we are still trying; quiet
        // after that, so a server that will never have LOD data costs nothing to sit on.
        final String line = "Chunksmith: the server still has no LOD data (asked " + RETRY.attempts()
                + " more time(s)); asking again in " + RETRY.delayMillis() / 1000L + "s";
        if (RETRY.attempts() <= 3) {
            LOGGER.info(line);
        } else {
            LOGGER.debug(line);
        }
        sendHello(false);
    }

    /** Tell the server what we can render, and how far. The join handshake. */
    private static void hello() {
        final boolean voxy = Renderers.hasVoxy();
        final boolean dh = Renderers.hasDh();
        if (!voxy && !dh) {
            LOGGER.info("Chunksmith: no LOD renderer installed (voxy / Distant Horizons); staying quiet");
            return;
        }
        final Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() != null) {
            host = client.getCurrentServer().ip;
            final int colon = host.lastIndexOf(':');
            if (colon > 0) {
                host = host.substring(0, colon);
            }
        }
        // Read the renderers' radius HERE and nowhere earlier. This is the first moment both renderers are
        // fully up, and asking voxy any sooner (e.g. from the init status line) class-loads its config
        // before voxy initializes and leaves voxy inert for the whole session -- silently. See VoxyRadius.
        // Cache what we find: every later hello (a retry, a token renewal) re-uses these numbers rather than
        // going back to voxy's config, which is exactly what that note forbids.
        capsVoxy = voxy;
        capsDh = dh;
        capsRadius = Renderers.configuredRadiusBlocks();
        // The sync interval, with its floor applied IN CODE (see CsLodClientConfig -- a config file is a
        // suggestion, and "sync-interval-seconds=1" must not become a poll storm against a server that is
        // trying to run a pregen). Read at join, when there is a game directory to read it from.
        LOGGER.info("Chunksmith: {}", CsLodClientConfig.load(ClientPlatform.gameDir().resolve("config")));
        LOGGER.info("Chunksmith: hello -- voxy={} dh={} radius={} blocks", voxy, dh, capsRadius);
        // Name the DH the player ACTUALLY has, at join, in our own log. We compile against the standalone
        // distanthorizonsapi artifact and support a wide range of DH releases, so the single most useful
        // fact in any bug report is which one was installed -- record it before anything can go wrong.
        // DhTarget hard-references DH types, so only touch it when DH is really present.
        if (dh) {
            LOGGER.info("Chunksmith: feeding {}", com.kishku7.chunksmith.lod.client.render.DhTarget.version());
        }
        sendHello(true);
    }

    /**
     * Put a hello on the wire.
     *
     * <p>One message, three callers: the join handshake, an empty-store retry, and a token renewal. The
     * server treats every one of them identically -- it answers with its current hello -- which is precisely
     * why none of this needed a new packet id. An older server, which knows nothing about retries, answers a
     * repeat hello exactly as it answered the first: correctly.
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
            // A 3.1.0-beta-3 server speaks v1. The hash field means something different there (a CRC32 of the
            // region's CONTENTS, which is what the server had to read every file in our radius to compute --
            // the bug), so the two protocols cannot interoperate and neither end pretends otherwise. Say it
            // in words a player can act on, once.
            LOGGER.warn("Chunksmith: this server speaks LOD protocol v{} and we speak v{} -- not fetching."
                            + " The server and the client must be on the same Chunksmith version"
                            + " (v1 is 3.1.0-beta-3 and earlier; v2 is 3.1.0-beta-4 and later).",
                    hello.protocolVersion(), CsLodProtocol.VERSION);
            return;
        }
        if (!hello.storeAvailable() || hello.dimensions().isEmpty()) {
            // NOT the end of the session. Say so once, in plain words, and keep asking -- the operator has
            // almost certainly not run the pregen yet, and when they do we want the player to see it without
            // being told to relog. (In singleplayer this is the normal pre-pregen state and the world's own
            // injector covers it, so retryTick stays out of it.)
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

        // WHICH dimension do we want? The one the player is standing in -- NEVER the first one the server
        // happened to list. That single line (`activeDimension = hello.dimensions().get(0)`) is what put
        // overworld terrain in players' Nether skies in 3.1.0-beta-2: the server lists its levels in
        // getAllLevels() order, the overworld comes first, and the client then pulled the overworld's store
        // for the whole session no matter where the player went.
        final String mine = CsLodDimension.current();
        if (mine.isEmpty()) {
            // No level yet -- we are still loading in. dimensionTick() will pick this up and re-hello the
            // moment there is a level to name.
            return;
        }
        playerDimension = mine;

        if (!serverDimensions.contains(mine)) {
            // The server HAS data -- just not for the dimension we are in. That is entirely normal (most
            // operators pregen the overworld and nothing else), and it is emphatically NOT a reason to
            // render some other dimension's terrain here. Say so once, and keep asking: the operator may
            // pregen this dimension later, and the player may walk back out of it.
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
            // The transition this whole retry machinery exists for. Name it.
            LOGGER.info("Chunksmith: the server NOW has LOD data for {} -- fetching it (after {} check(s),"
                            + " with no relog)", mine, RETRY.attempts());
            awaitingStore = false;
            RETRY.reset();
        }

        // Only ANNOUNCE on the hello that arms us -- for THIS dimension. The later ones are token renewals,
        // and a renewal every few minutes of travel must not re-narrate the connection. A dimension change
        // DOES re-announce, because it is genuinely a different world and a different store.
        final boolean arming = activeDimension.isEmpty();
        if (backchannelPort == 0) {
            // The operator has not opened the port. Not an error, and not the end: we ask for the data
            // in-band instead. It is much slower -- it rides the gameplay connection -- but it works
            // everywhere, which is the whole point of having a floor.
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
        // The dimension is server-supplied and is about to become a filesystem path in every transport
        // below (HTTP downloader, in-band reassembler, injector). Gate it once at the top too, so a
        // malformed id never reaches any of them, and free the busy latch we took to get here.
        if (CsLodStore.dimensionDir(root, index.dimension()) == null) {
            LOGGER.warn("Chunksmith: server sent a malformed dimension id; ignoring the region index");
            busy.set(false);
            return;
        }
        // REMEMBER IT. This is the set the sync poll folds against (see summary()), and it is also what
        // carries each region's freshness token all the way to the injector -- which needs it, because a
        // region whose token has MOVED must be re-injected, not skipped as "already drawn". Carrying bare
        // coordinates from here to the injector, as we used to, is precisely what would have thrown away
        // every re-fetched, still-growing region under a standing player's feet.
        lastIndex = index.regions();
        lastSyncMillis = System.currentTimeMillis();

        if (backchannelPort == 0 || token.isEmpty() || host.isEmpty()) {
            inBand(index, root);
            return;
        }

        downloader = new CsLodDownloader(root);

        // Off the game thread. A download must never make the game stutter, and the player must be able to
        // keep playing while it runs. Injection follows on the same thread, for the same reason.
        final Thread worker = new Thread(() -> {
            try {
                // ONE cheap probe before we queue anything. Without it, an advertised-but-unreachable port
                // costs a full connect timeout PER REGION -- measured at ~30s of dead air on a 9-region
                // store before the fallback fires, and it scales with the store. The player sees nothing and
                // is told nothing for half a minute. A single socket answers the same question in 2s.
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
                // and drops, a server that dies mid-transfer). Do not just fail -- that would leave a player
                // staring at empty sky with a "0 fetched, N failed" line nobody reads. Drop to the in-band
                // channel, which rides a connection we know works, and say why.
                if (current.fetched() == 0 && current.failed() > 0) {
                    LOGGER.warn("Chunksmith: the backchannel on port {} accepted a connection but"
                                    + " served nothing ({} regions failed); falling back to the in-band"
                                    + " channel (slower)", backchannelPort, current.failed());
                    backchannelPort = 0;
                    Minecraft.getInstance().execute(() -> inBand(index, root));
                    return;
                }

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
     * The slow path: ask for the regions down the game connection.
     *
     * <p>Used when the server never opened a backchannel port, or when it advertised one we cannot reach.
     * Asks only for what we are actually missing, exactly as the fast path does -- the cache rule does not
     * change just because the transport did.
     */
    private static void inBand(final CsLodMessages.RegionIndex index, final Path root) {
        inBandRoot = root;
        inBandDimension = index.dimension();
        inBandRegions = index.regions();

        // The manifest is the cache check on BOTH transports -- the rule does not change because the
        // transport did. It is also where each region's freshness token is recorded as the slices land, so
        // the next index (and the next sync poll) can tell what we hold.
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
     * Reassemble an in-band region file, slice by slice.
     *
     * <p>Written to a .part file and MOVED into place only when the last slice lands, so a transfer that is
     * cut off half way can never be mistaken for a cached region on the next join.
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
            // slice.dimension() is a distinct wire value on its own message, so gate it here too rather
            // than assuming the index gate covered it (D20 -- every consumer validates the same field).
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

            // Record what the SERVER said about the region we have just assembled. The in-band REQUEST
            // echoes coordinates only, so the token has to come from the index that prompted the fetch --
            // which is exactly what inBandRegions is holding.
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

    /** Stop the flow. The client can always stop. */
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
        com.kishku7.chunksmith.lod.client.render.LodInjector.reset();
    }

    /**
     * The client's own store, keyed by server, so two servers never mix.
     * {@code .minecraft/chunksmith/lod/<server>}
     */
    private static Path storeRoot() {
        final String key = host.isEmpty() ? "unknown" : host.replaceAll("[^a-zA-Z0-9._-]", "_");
        return ClientPlatform.gameDir().resolve("chunksmith").resolve("lod").resolve(key);
    }

    private static void send(final byte[] data) {
        ClientPlatform.sendToServer(data);
    }
}
