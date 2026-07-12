package com.kishku7.chunksmith.lod.net;

import com.kishku7.chunksmith.lod.LodSupport;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.zip.CRC32;

/**
 * Server side of the Chunksmith LOD protocol.
 *
 * <p>The client PULLS: it says hello (telling us which renderers it actually has), asks for a region
 * index, works out what it is missing, and fetches it -- over the HTTP backchannel when that is
 * available, in-band when it is not. It can stop at any time. The server never pushes uninvited.
 *
 * <p>Refuses a client with no renderer: there is no point burning a server's bandwidth on a player who
 * cannot draw the result.
 */
public final class CsLodServerNet {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private static final CsLodTokens TOKENS = new CsLodTokens();

    /** The radius each client's renderer is actually configured to draw, in blocks. */
    private static final Map<UUID, Integer> RADIUS = new java.util.concurrent.ConcurrentHashMap<>();
    private static CsLodHttpServer http;
    private static MinecraftServer server;

    private CsLodServerNet() {
    }

    /** Register the channel. Called at mod init, before any server exists. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(CsLodPayload.TYPE, CsLodPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CsLodPayload.TYPE, CsLodPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(CsLodPayload.TYPE, (payload, context) ->
                context.server().execute(() -> handle(context.player(), payload.data())));

        // A token must never outlive the session that earned it.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> {
            TOKENS.revoke(handler.getPlayer().getUUID());
            CsLodInBandSender.forget(handler.getPlayer().getUUID());
            RADIUS.remove(handler.getPlayer().getUUID());
        });
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
        if (!lodEnabled()) {
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
        http = new CsLodHttpServer(root, TOKENS, CsLodServerNet::isOnline);
        // Same interface the game is bound to, game port + 1. Nothing for an operator to configure.
        // A bind failure is a log line, not an error: the client falls back to the in-band channel.
        http.start(current.getLocalIp(), current.getPort());
    }

    private static boolean lodEnabled() {
        return com.kishku7.chunksmith.ChunksmithProvider.isLoaded()
                && com.kishku7.chunksmith.ChunksmithProvider.get().getConfig().isLodEnabled();
    }

    public static void onServerStopped() {
        if (http != null) {
            http.stop();
            http = null;
        }
        TOKENS.clear();
        server = null;
    }

    /** For the status command. */
    public static String describe() {
        final String inBand = CsLodInBandSender.pending() > 0
                ? " | in-band backlog: " + CsLodInBandSender.pending() + " slices" : "";
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

    private static void handle(final ServerPlayer player, final byte[] data) {
        if (data.length == 0) {
            return;
        }
        try (DataInputStream in = CsLodMessages.reader(data)) {
            final byte id = in.readByte();
            switch (id) {
                case CsLodProtocol.C2S_HELLO -> hello(player, CsLodMessages.decodeClientHello(in));
                case CsLodProtocol.C2S_REQUEST_INDEX -> index(player, in.readUTF());
                case CsLodProtocol.C2S_REQUEST_REGIONS -> inBand(player, in);
                case CsLodProtocol.C2S_CANCEL -> {
                    CsLodInBandSender.cancel(player);
                    LOGGER.debug("Chunksmith: LOD transfer cancelled by client");
                }
                default -> LOGGER.warn("Chunksmith: unknown LOD message id " + id);
            }
        } catch (final IOException e) {
            LOGGER.warn("Chunksmith: malformed LOD message from " + player.getGameProfile().name() + ": " + e);
        }
    }

    private static void hello(final ServerPlayer player, final CsLodMessages.ClientHello hello) throws IOException {
        if (hello.protocolVersion() != CsLodProtocol.VERSION) {
            LOGGER.info("Chunksmith: " + player.getGameProfile().name() + " speaks LOD protocol v"
                    + hello.protocolVersion() + ", we speak v" + CsLodProtocol.VERSION + " -- not serving");
            return;
        }
        if (!hello.hasVoxy() && !hello.hasDh()) {
            // No renderer, no point. Say so and stop -- do not burn bandwidth on data nobody can draw.
            send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                    CsLodProtocol.VERSION, false, 0, "", List.of())));
            return;
        }

        final Path root = storeBase();
        final boolean available = root != null && Files.isDirectory(root);
        final int port = http == null ? 0 : http.getPort();

        // The token is issued HERE, over a connection Mojang has already authenticated. That is the whole
        // point: a UUID or a name proves nothing (both are public), but only a genuinely joined player can
        // ever receive this.
        final String token = (available && port != 0)
                ? TOKENS.issue(player.getUUID(), addressOf(player))
                : "";

        send(player, CsLodMessages.encode(new CsLodMessages.ServerHello(
                CsLodProtocol.VERSION, available, port, token, dimensions())));

        RADIUS.put(player.getUUID(), Math.max(16, hello.radiusBlocks()));

        LOGGER.info("Chunksmith: LOD hello from " + player.getGameProfile().name()
                + " (voxy=" + hello.hasVoxy() + " dh=" + hello.hasDh() + " radius=" + hello.radiusBlocks()
                + ") -> store=" + available + " backchannel=" + (port == 0 ? "none (in-band)" : port));
    }

    /**
     * The fallback: the operator has no backchannel port open, so we drip the regions down the game
     * connection instead. Slow on purpose -- gameplay wins, LOD fills the gaps.
     */
    private static void inBand(final ServerPlayer player, final DataInputStream in) throws IOException {
        final String dimension = in.readUTF();
        final int count = in.readInt();
        final List<CsLodMessages.RegionEntry> wanted = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            wanted.add(new CsLodMessages.RegionEntry(in.readInt(), in.readInt(), 0L, 0L));
        }
        final Path root = storeBase();
        if (root == null) {
            return;
        }
        CsLodInBandSender.queue(player, root, dimension, wanted);
        LOGGER.info("Chunksmith: in-band LOD fetch for {} -- {} regions (no backchannel; this is the slow path)",
                player.getGameProfile().name(), wanted.size());
    }

    /** Drip-feed the in-band queues. Wired to the server tick. */
    public static void tick(final MinecraftServer current) {
        for (final ServerPlayer player : current.getPlayerList().getPlayers()) {
            CsLodInBandSender.tick(player);
        }
    }

    /** Send raw protocol bytes to a player. */
    public static void sendTo(final ServerPlayer player, final byte[] data) {
        send(player, data);
    }

    private static void index(final ServerPlayer player, final String dimension) throws IOException {
        final Path root = storeBase();
        if (root == null) {
            return;
        }
        final Path dir = root.resolve(dimension);
        final List<CsLodMessages.RegionEntry> regions = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (final Path file : files.toList()) {
                    final String name = file.getFileName().toString();
                    if (!name.endsWith(".cslod")) {
                        continue;
                    }
                    final String[] parts = name.split("\\.");
                    if (parts.length != 4) {
                        continue;
                    }
                    try {
                        final int regionX = Integer.parseInt(parts[1]);
                        final int regionZ = Integer.parseInt(parts[2]);
                        if (!inRange(player, regionX, regionZ)) {
                            continue;
                        }
                        regions.add(new CsLodMessages.RegionEntry(
                                regionX, regionZ, hash(file), Files.size(file)));
                    } catch (final NumberFormatException ignored) {
                        // not one of ours
                    }
                }
            }
        }
        send(player, CsLodMessages.encode(new CsLodMessages.RegionIndex(dimension, regions)));
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
    private static boolean inRange(final ServerPlayer player, final int regionX, final int regionZ) {
        final int radius = RADIUS.getOrDefault(player.getUUID(), CsLodProtocol.DEFAULT_RADIUS_BLOCKS);
        final int minX = regionX * 512;
        final int minZ = regionZ * 512;
        final int maxX = minX + 511;
        final int maxZ = minZ + 511;

        final int px = (int) player.getX();
        final int pz = (int) player.getZ();

        // Distance from the player to the nearest point of the region box.
        final int dx = Math.max(0, Math.max(minX - px, px - maxX));
        final int dz = Math.max(0, Math.max(minZ - pz, pz - maxZ));
        return (long) dx * dx + (long) dz * dz <= (long) radius * radius;
    }

    /**
     * Content hash of a region file -- how the client works out what it already has.
     *
     * <p>CRC32 over the bytes: cheap, and this is a cache-freshness check, not a security boundary (the
     * token is the security boundary). Cached per (path, mtime, size) would be the next optimization if a
     * huge store makes indexing slow.
     */
    private static long hash(final Path file) throws IOException {
        final CRC32 crc = new CRC32();
        crc.update(Files.readAllBytes(file));
        return crc.getValue();
    }

    private static List<String> dimensions() {
        final List<String> names = new ArrayList<>();
        final MinecraftServer current = server;
        if (current == null) {
            return names;
        }
        final Path root = storeBase();
        if (root == null) {
            return names;
        }
        for (final ServerLevel level : current.getAllLevels()) {
            final Path dir = LodSupport.storeRoot(level);
            if (Files.isDirectory(dir)) {
                names.add(dir.getFileName().toString());
            }
        }
        return names;
    }

    private static Path storeBase() {
        final MinecraftServer current = server;
        return current == null ? null : LodSupport.storeRootBase(current);
    }

    private static String addressOf(final ServerPlayer player) {
        final var address = player.connection.getRemoteAddress();
        if (address instanceof final InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return "";
    }

    private static void send(final ServerPlayer player, final byte[] data) {
        ServerPlayNetworking.send(player, new CsLodPayload(data));
    }
}
