package com.kishku7.chunksmith.lod.client.net;

import com.kishku7.chunksmith.lod.client.CsLodCache;
import com.kishku7.chunksmith.lod.client.CsLodDownloader;
import com.kishku7.chunksmith.lod.client.CsLodStore;
import com.kishku7.chunksmith.lod.client.Renderers;
import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
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

    private static volatile CsLodDownloader downloader;
    private static volatile String token = "";
    private static volatile int backchannelPort;
    private static volatile String host = "";

    /** The dimension we are pulling for -- set from the server's hello, and re-used by every refresh. */
    private static volatile String activeDimension = "";

    /** One fetch at a time. A travel refresh must never race the join fetch, or itself. */
    private static final AtomicBoolean busy = new AtomicBoolean();

    private static volatile double lastIndexX;
    private static volatile double lastIndexZ;
    private static volatile long lastIndexMillis;

    /** In-band fallback state: where the slices are being assembled, and for which dimension. */
    private static volatile Path inBandRoot;
    private static volatile String inBandDimension = "";
    private static volatile List<int[]> inBandRegions = List.of();
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
        if (activeDimension.isEmpty() || busy.get()) {
            return;
        }
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastIndexMillis < MIN_REFRESH_MILLIS) {
            return;
        }
        final double dx = player.getX() - lastIndexX;
        final double dz = player.getZ() - lastIndexZ;
        if (dx * dx + dz * dz < REFRESH_MOVE_BLOCKS * REFRESH_MOVE_BLOCKS) {
            return;
        }
        requestIndex(activeDimension);
    }

    /** Tell the server what we can render, and how far. */
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
        final int radius = Renderers.configuredRadiusBlocks();
        LOGGER.info("Chunksmith: hello -- voxy={} dh={} radius={} blocks", voxy, dh, radius);
        // Name the DH the player ACTUALLY has, at join, in our own log. We compile against the standalone
        // distanthorizonsapi artifact and support a wide range of DH releases, so the single most useful
        // fact in any bug report is which one was installed -- record it before anything can go wrong.
        // DhTarget hard-references DH types, so only touch it when DH is really present.
        if (dh) {
            LOGGER.info("Chunksmith: feeding {}", com.kishku7.chunksmith.lod.client.render.DhTarget.version());
        }
        try {
            send(CsLodMessages.encode(new CsLodMessages.ClientHello(
                    CsLodProtocol.VERSION, voxy, dh, radius)));
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
                case CsLodProtocol.S2C_INDEX -> index(CsLodMessages.decodeRegionIndex(in));
                case CsLodProtocol.S2C_CHUNK -> slice(CsLodMessages.decodeRegionSlice(in));
                case CsLodProtocol.S2C_DONE -> {
                    LOGGER.info("Chunksmith: in-band transfer complete");
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
        if (hello.protocolVersion() != CsLodProtocol.VERSION) {
            LOGGER.info("Chunksmith: server speaks LOD protocol v" + hello.protocolVersion()
                    + ", we speak v" + CsLodProtocol.VERSION + " -- not fetching");
            return;
        }
        if (!hello.storeAvailable() || hello.dimensions().isEmpty()) {
            LOGGER.info("Chunksmith: server has no pregenerated LOD data");
            return;
        }
        token = hello.token();
        backchannelPort = hello.backchannelPort();

        if (backchannelPort == 0) {
            // The operator has not opened the port. Not an error, and not the end: we ask for the data
            // in-band instead. It is much slower -- it rides the gameplay connection -- but it works
            // everywhere, which is the whole point of having a floor.
            LOGGER.info("Chunksmith: server has LOD data but no backchannel; using the in-band"
                    + " fallback (slower)");
        } else {
            LOGGER.info("Chunksmith: server has LOD data for {}; backchannel on port {}",
                    hello.dimensions(), backchannelPort);
        }
        activeDimension = hello.dimensions().get(0);
        requestIndex(activeDimension);
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
        final List<int[]> regions = new ArrayList<>(index.regions().size());
        for (final CsLodMessages.RegionEntry entry : index.regions()) {
            regions.add(new int[]{entry.regionX(), entry.regionZ()});
        }

        if (backchannelPort == 0 || token.isEmpty() || host.isEmpty()) {
            inBand(index, root, regions);
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
                    Minecraft.getInstance().execute(() -> inBand(index, root, regions));
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
                    Minecraft.getInstance().execute(() -> inBand(index, root, regions));
                    return;
                }

                com.kishku7.chunksmith.lod.client.render.LodInjector.injectRegions(root, index.dimension(), regions,
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
    private static void inBand(final CsLodMessages.RegionIndex index, final Path root, final List<int[]> regions) {
        inBandRoot = root;
        inBandDimension = index.dimension();
        inBandRegions = regions;

        final List<CsLodMessages.RegionEntry> wanted = new ArrayList<>();
        for (final CsLodMessages.RegionEntry entry : index.regions()) {
            if (!CsLodCache.have(root, index.dimension(), entry)) {
                wanted.add(entry);
            }
        }
        LOGGER.info("Chunksmith: in-band fetch -- {} regions within my radius, {} already cached,"
                        + " {} to fetch (this is the slow path)",
                index.regions().size(), index.regions().size() - wanted.size(), wanted.size());
        if (wanted.isEmpty()) {
            injectAsync(root, index.dimension(), regions);
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
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: failed to store in-band region {}: {}", key, e.toString());
        }
    }

    /** Hand the new regions to the renderers, off the game thread. */
    private static void injectAsync(final Path root, final String dimension, final List<int[]> regions) {
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
        backchannelPort = 0;
        host = "";
        activeDimension = "";
        busy.set(false);
        lastIndexMillis = 0L;
        inBandRoot = null;
        inBandDimension = "";
        inBandRegions = List.of();
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
