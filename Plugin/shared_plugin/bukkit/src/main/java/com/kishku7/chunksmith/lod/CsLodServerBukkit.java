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

package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.lod.net.CsLodCapNotice;
import com.kishku7.chunksmith.lod.net.CsLodControl;
import com.kishku7.chunksmith.lod.net.CsLodHttpServer;
import com.kishku7.chunksmith.lod.net.CsLodIndexScan;
import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import com.kishku7.chunksmith.lod.net.CsLodStoreScan;
import com.kishku7.chunksmith.lod.net.CsLodTokens;
import com.kishku7.chunksmith.platform.Config;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import com.kishku7.chunksmith.ChunksmithProvider;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Serves the CSLOD store to clients from a Bukkit/Paper server.
 *
 * <p>The plugin has generated LOD data since 3.2.0, and every piece of the machinery for
 * sending it (the wire format, the token store, the HTTP backchannel) has shipped inside the
 * plugin jar all along, because those classes live in {@code shared_common}. What was missing
 * was the code that connects them: nothing registered a channel, nothing started the HTTP
 * server, nothing answered a client (mod_support #18). This is that missing connection.
 *
 * <p>The client drives the exchange: hello, then ask for the region index, then fetch over HTTP
 * whatever the index says it lacks. A server that answers only the hello looks completely
 * healthy. It logs the greeting, mints a token, names its port, and serves nothing at all,
 * because the client is waiting on an index that never comes. The first cut of this class did
 * exactly that, with the counters reading {@code 0 files} beside a live token. Both requests
 * are answered here now.
 *
 * <p><b>Bukkit will not let us reply unless we ask it to.</b> Bukkit keeps a per-player set of
 * the channels the client announced with a {@code minecraft:register} plugin message, and
 * {@code sendPluginMessage} does nothing at all for a channel outside that set: no exception,
 * no log line, no packet. A modern Fabric client negotiates the other direction perfectly (its
 * hello reaches us) but puts nothing in that set, so the server hears the client and the client
 * never hears the server. See {@link #ensureChannel(Player)}.
 *
 * <p>Not implemented here: the in-band fallback, streaming region data down the plugin channel
 * when the HTTP port is unreachable. On the mod that path exists because a firewalled port must
 * not mean no LOD at all; here a client that cannot reach the port gets nothing, and the log
 * names the port it should have been able to reach. Open the port, or set {@code
 * lod-backchannel-port} to one the host allows.
 *
 * <p>Dimension roots differ from the mod. A mod-loader server keeps every dimension under one
 * save root; Bukkit gives each world its own folder, so this hands {@link CsLodHttpServer} a
 * resolver rather than a single path, and nobody's existing store has to move. That resolver is
 * also what makes the wire dimension id safe: it is matched against the keys of the worlds that
 * are actually loaded, so a malformed or hostile id resolves to nothing rather than to a path.
 */
public final class CsLodServerBukkit implements PluginMessageListener {

    private static final Logger LOGGER = Logger.getLogger("Chunksmith");

    /** Silences a repeated "index was capped" line per player; see CsLodCapNotice. */
    private static final CsLodCapNotice CAP_NOTICE = new CsLodCapNotice();
    private static final String CHANNEL = CsLodProtocol.NAMESPACE + ":" + CsLodProtocol.CHANNEL;

    /**
     * Ceiling on the radius a client may ask us to scan, mirroring the mod. A client that
     * claims a draw distance of two million blocks gets the largest one we are prepared to walk
     * the store for.
     */
    private static final int MAX_RADIUS_BLOCKS = 16384;

    private static final CsLodTokens TOKENS = new CsLodTokens();

    /**
     * Players who have actually spoken to us on {@link #CHANNEL}.
     *
     * <p>This is the only warrant for {@link #ensureChannel(Player)} forcing a channel
     * registration, and the reason a re-advertise does not spray a payload at every vanilla
     * player on the server. A player lands here because they sent us a hello, which is proof
     * they understand the channel.
     */
    private static final Set<UUID> SPOKEN = ConcurrentHashMap.newKeySet();

    /** The draw distance each client told us about, so the index is filtered to what it can show. */
    private static final Map<UUID, Integer> RADIUS = new ConcurrentHashMap<>();

    /** One scan in flight per player. A client that asks twice gets one answer, not two walks. */
    private static final Set<UUID> SCANNING = ConcurrentHashMap.newKeySet();

    private static Plugin plugin;
    private static CsLodHttpServer http;
    private static CsLodServerBukkit listener;
    private static volatile ExecutorService scanPool;

    /** Resolved once, lazily; null means "looked and did not find it". See {@link #forceChannel}. */
    private static Method addChannelMethod;
    private static boolean addChannelResolved;
    private static boolean forcedChannelLogged;

    private CsLodServerBukkit() {
    }

    /** Called from {@code onEnable}, after the config exists. Safe to call when LOD is off. */
    public static void enable(Plugin owner, Config config) {
        plugin = owner;
        if (!LodSupport.lodEnabled(config)) {
            return;
        }

        listener = new CsLodServerBukkit();
        owner.getServer().getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
        owner.getServer().getMessenger().registerIncomingPluginChannel(owner, CHANNEL, listener);

        scanPool = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "chunksmith-lod-scan");
            thread.setDaemon(true);
            return thread;
        });

        http = new CsLodHttpServer(CsLodServerBukkit::rootFor, TOKENS, CsLodServerBukkit::isOnline);
        int bound = http.start(bindAddress(owner), owner.getServer().getPort(),
                config.getLodBackchannelPort(), config.getLodBackchannelBindAddress());

        if (bound == 0) {
            // Not fatal, but on this platform there is no in-band fallback to quietly succeed with,
            // so an operator who does not read this gets silence and no LOD. Say it plainly.
            LOGGER.warning("Chunksmith: the LOD backchannel is NOT running, so players will not"
                    + " receive LOD from this server. Open the port, or set lod-backchannel-port in"
                    + " config.yml to one your host allows.");
        }

        CsLodControl.register(
                CsLodServerBukkit::rebind,
                () -> owner.getServer().getPort(),
                () -> http == null ? "backchannel: not running" : http.describe());
    }

    /** Called from {@code onDisable}. */
    public static void disable() {
        CsLodControl.clear();
        if (plugin != null && listener != null) {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, listener);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
            listener = null;
        }
        ExecutorService pool = scanPool;
        scanPool = null;
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        TOKENS.clear();
        SPOKEN.clear();
        RADIUS.clear();
        SCANNING.clear();
    }

    /**
     * Returns the host to put in a hello, or empty to let the client use the address it connected
     * to. Read per hello, so a /cs set is in force for the re-advertisement it triggers.
     */
    private static String advertisedHost() {
        Plugin owner = plugin;
        return owner == null ? "" : ChunksmithProvider.get().getConfig().getLodBackchannelHost();
    }

    /** A token must never outlive the session that earned it. Wired to PlayerQuitEvent. */
    public static void onQuit(UUID player) {
        TOKENS.revoke(player);
        CAP_NOTICE.forget(player);
        SPOKEN.remove(player);
        RADIUS.remove(player);
        SCANNING.remove(player);
    }

    /** Move the backchannel to the configured port, live. Mirrors the mod's rebind. */
    public static int rebind() {
        if (plugin == null) {
            return 0;
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        Config config = ChunksmithProvider.get().getConfig();
        if (!LodSupport.lodEnabled(config)) {
            return 0;
        }
        http = new CsLodHttpServer(CsLodServerBukkit::rootFor, TOKENS, CsLodServerBukkit::isOnline);
        int bound = http.start(bindAddress(plugin), plugin.getServer().getPort(),
                config.getLodBackchannelPort(), config.getLodBackchannelBindAddress());
        readvertise(bound);
        return bound;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        // Strip the length prefix. Fabric frames the payload with buf.writeByteArray(), which is a
        // VarInt length followed by the bytes; the mod's receiver calls readByteArray() and never
        // sees it. Bukkit hands us the packet payload raw, so the prefix is still on the front and
        // the first byte is a length, not a message id.
        byte[] body = stripLengthPrefix(message);
        if (body.length == 0) {
            return;
        }
        try (final DataInputStream in = CsLodMessages.reader(body)) {
            switch (in.readByte()) {
                case CsLodProtocol.C2S_HELLO -> {
                    CsLodMessages.ClientHello greeting = CsLodMessages.decodeClientHello(in);
                    RADIUS.put(player.getUniqueId(), clampRadius(greeting.radiusBlocks()));
                    SPOKEN.add(player.getUniqueId());
                    hello(player);
                }
                case CsLodProtocol.C2S_REQUEST_INDEX -> dispatch(player, in.readUTF(), false);
                case CsLodProtocol.C2S_REQUEST_SUMMARY -> dispatch(player, in.readUTF(), true);
                default -> {
                    // C2S_REQUEST_REGIONS and C2S_CANCEL drive the IN-BAND transfer, which this
                    // platform does not serve. Dropping them silently is correct: a client only asks
                    // in-band when we advertised no backchannel port, and answering with an error it
                    // has no handler for would be worse than the silence it already copes with.
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Chunksmith: malformed LOD message from " + player.getName() + ": " + e);
        }
    }

    // TODO: no in-band path here yet; a blocked port just means no LOD
    private static void hello(Player player) {
        List<String> dims = dimensions();
        LOGGER.info("Chunksmith: LOD hello from " + player.getName()
                + ": " + dims.size() + " dimension(s) to offer");
        ensureChannel(player);
        boolean available = !dims.isEmpty();
        int port = http == null ? 0 : http.getPort();
        // No data or no port means no credential. A token that can fetch nothing is how an operator
        // ends up reading "1 live token, 0 files" and rightly wondering what it means.
        String token = (available && port != 0)
                ? TOKENS.issue(player.getUniqueId(), addressOf(player))
                : "";
        try {
            player.sendPluginMessage(plugin, CHANNEL, withLengthPrefix(CsLodMessages.encode(
                    new CsLodMessages.ServerHello(CsLodProtocol.VERSION, available, port, token, dims,
                            advertisedHost()))));
        } catch (IOException e) {
            LOGGER.warning("Chunksmith: could not answer the LOD hello from "
                    + player.getName() + ": " + e);
        }
    }

    /**
     * Answer "what have you got near me?" -- the request that actually starts a download.
     *
     * <p>Snapshot on the calling (main) thread, scan on ours. A readdir plus one stat per
     * region has no business on a tick, and the snapshot is the thread boundary: after this
     * method returns, the scan never touches a game object again.
     *
     * <p>The index is served for the dimension the player is standing in, not the one they
     * asked about. An index is a set of regions filtered by a radius measured from a position,
     * and a position only means something in one world; answering the overworld's index to
     * somebody in the Nether returns overworld regions selected by Nether coordinates, which
     * the client will then draw. A 3.1.0-beta-2 client asks exactly that way, and no patch to
     * this server can change what is already in a player's mods folder.
     */
    private static void dispatch(Player player, String requested, boolean summaryOnly) {
        String dimension = LodSupport.dimensionKey(player.getWorld());
        Path dir = rootFor(dimension);
        if (dir == null) {
            return;
        }
        if (!dimension.equals(requested)) {
            LOGGER.info("Chunksmith: " + player.getName() + " asked for the LOD index of " + requested
                    + " while standing in " + dimension + ", serving " + dimension + " instead.");
        }
        UUID uuid = player.getUniqueId();
        if (!SCANNING.add(uuid)) {
            return;
        }
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        int radius = RADIUS.getOrDefault(uuid, CsLodProtocol.DEFAULT_RADIUS_BLOCKS);
        String name = player.getName();

        ExecutorService pool = scanPool;
        if (pool == null) {
            SCANNING.remove(uuid);
            return;
        }
        try {
            pool.execute(() -> scan(uuid, name, dimension, dir, px, pz, radius, summaryOnly));
        } catch (RejectedExecutionException e) {
            SCANNING.remove(uuid);   // the server is stopping; nothing to answer and nothing to say
        }
    }

    /**
     * The scan and the reply both happen off the main thread.
     *
     * <p>The mod hops back to the tick to send, because a loader's channel API is not obviously
     * thread-safe. Bukkit's is: a plugin message becomes a packet write on the player's Netty
     * channel, which is what every scheduler-async plugin in existence already relies on.
     * Sending from here keeps this class off the Bukkit scheduler entirely, which is also what
     * lets it behave the same way on Folia, where there is no single main thread to hop back
     * to.
     */
    private static void scan(final UUID uuid, final String name, final String dimension,
                             final Path dir, final int px, final int pz, final int radius,
                             final boolean summaryOnly) {
        try {
            long budgetMb = ChunksmithProvider.get().getConfig().getLodIndexBudgetMb();
            CsLodIndexScan.Result scanned = CsLodIndexScan.scan(dir,
                    new CsLodIndexScan.Request(dimension, px, pz, radius, budgetMb * 1024L * 1024L),
                    System.currentTimeMillis());
            // Throttled the same way the mod throttles it, from the same class, so the two platforms
            // cannot drift into saying different things about the same cap. See CsLodCapNotice.
            if (scanned.capped() && CAP_NOTICE.shouldWarn(uuid, System.currentTimeMillis())) {
                LOGGER.warning("Chunksmith: LOD index for " + name + " capped at "
                        + scanned.regions().size() + " of " + scanned.found() + " regions ("
                        + (scanned.bytes() / (1024 * 1024)) + " MB, radius " + radius + "): "
                        + CsLodCapNotice.explain(scanned, budgetMb));
            }
            byte[] message = summaryOnly
                    ? CsLodMessages.encode(new CsLodMessages.RegionSummary(dimension,
                            scanned.regions().size(), CsLodIndexScan.aggregate(scanned.regions())))
                    : CsLodMessages.encode(new CsLodMessages.RegionIndex(dimension, scanned.regions()));

            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                online.sendPluginMessage(plugin, CHANNEL, withLengthPrefix(message));
            }
        } catch (IOException e) {
            LOGGER.warning("Chunksmith: could not scan the LOD store for " + name + ": " + e);
        } finally {
            SCANNING.remove(uuid);
        }
    }

    /**
     * Returns the client's draw distance, believed within reason. Nonsense falls back to the default.
     *
     * @return the radius to scan, in blocks
     */
    private static int clampRadius(int requested) {
        if (requested <= 0) {
            return CsLodProtocol.DEFAULT_RADIUS_BLOCKS;
        }
        return Math.min(requested, MAX_RADIUS_BLOCKS);
    }

    /**
     * Make sure Bukkit will actually deliver our messages to this player.
     *
     * <p>Bukkit only sends a plugin message on a channel the player announced with {@code
     * minecraft:register}; for anything else {@code sendPluginMessage} returns having done
     * nothing whatsoever. A modern Fabric client does not put our channel in that set. Its own
     * networking API negotiates capability separately and the register packet either never goes
     * out or goes out during the CONFIGURATION phase, where the play-phase set does not see it.
     * The result is a server that receives the hello, logs it, answers it, and delivers
     * nothing.
     *
     * <p>Only ever called for a player who has already SPOKEN to us on this channel, so this is
     * not a guess about what the client understands: it is a correction of a bookkeeping gap
     * between two mod platforms, applied to a client that has demonstrated it speaks the
     * protocol. A client that never says hello is never touched.
     *
     * <p>Falls back to the warning it replaces if the registration cannot be forced, because
     * the alternative (Bukkit's silence) costs an hour to diagnose the first time and this
     * warning is the only thing in the log that names the real cause.
     */
    private static void ensureChannel(Player player) {
        if (player.getListeningPluginChannels().contains(CHANNEL)) {
            return;
        }
        if (forceChannel(player)) {
            if (!forcedChannelLogged) {
                forcedChannelLogged = true;
                LOGGER.info("Chunksmith: " + player.getName() + " speaks " + CHANNEL + " but did not"
                        + " announce it to the server, which is normal for a Fabric client. Registering"
                        + " the channel on their behalf so replies can be delivered. Logged once.");
            }
            return;
        }
        LOGGER.warning("Chunksmith: " + player.getName() + " spoke to us on " + CHANNEL
                + " but has not registered that channel, so the reply cannot be delivered."
                + " Channels it did register: " + player.getListeningPluginChannels());
    }

    /**
     * Adds the channel to the server's per-player set the same way an incoming {@code
     * minecraft:register} would.
     *
     * <p>Reflective because Bukkit's {@code Player} interface exposes the set read-only ({@code
     * getListeningPluginChannels}) and offers no way to add to it; the implementation class has
     * always had a public {@code addChannel(String)} and that is what the vanilla register path
     * itself calls. Looked up by name off the live object, so no server package is named and no
     * relocated or version-stamped class has to be guessed at. Nothing here touches {@code
     * net.minecraft}.
     *
     * <p>Every failure returns false rather than throwing. A server whose implementation has
     * moved on must keep running and keep generating, and the caller has a plain-language
     * warning ready for exactly that case.
     *
     * @return true if the channel was added
     */
    private static boolean forceChannel(Player player) {
        if (!addChannelResolved) {
            addChannelResolved = true;
            try {
                addChannelMethod = player.getClass().getMethod("addChannel", String.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                addChannelMethod = null;
                LOGGER.warning("Chunksmith: this server has no addChannel(String) on "
                        + player.getClass().getName() + ", so LOD replies cannot be delivered to"
                        + " clients that do not announce the channel themselves: " + e);
            }
        }
        if (addChannelMethod == null) {
            return false;
        }
        try {
            addChannelMethod.invoke(player, CHANNEL);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warning("Chunksmith: could not register " + CHANNEL + " for "
                    + player.getName() + ": " + e);
            return false;
        }
        return player.getListeningPluginChannels().contains(CHANNEL);
    }

    /**
     * Re-tell every client that has spoken to us the new port, with a fresh token. Mirrors the
     * mod's readvertise. Players who never said hello are skipped. They have no client to tell,
     * and the count in the log should mean "clients that will act on this", not "bodies on the
     * server".
     */
    private static void readvertise(int port) {
        int told = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!SPOKEN.contains(player.getUniqueId())) {
                continue;
            }
            hello(player);
            told++;
        }
        LOGGER.info("Chunksmith: LOD backchannel moved to port "
                + (port == 0 ? "none" : String.valueOf(port))
                + " -- " + told + " connected client(s) re-issued a token. No relog needed.");
    }

    /**
     * Returns the dimensions that actually have something to serve, by the same rule the mod uses.
     *
     * @return the servable dimension ids
     */
    private static List<String> dimensions() {
        List<Path> dirs = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            dirs.add(LodSupport.storeRoot(world));
        }
        return CsLodStoreScan.servable(dirs, System.currentTimeMillis());
    }

    /**
     * Bukkit worlds do not share a parent, so each dimension resolves to its own world folder.
     *
     * <p>Also the containment check for a dimension id that came off the wire. It is compared
     * against the keys of the loaded worlds, so there is no string to sanitise and nothing to
     * escape from. An id we do not recognise resolves to null and is answered with silence.
     */
    private static Path rootFor(String dimension) {
        for (World world : Bukkit.getWorlds()) {
            if (LodSupport.dimensionKey(world).equals(dimension)) {
                return LodSupport.storeRoot(world);
            }
        }
        return null;
    }

    /**
     * Removes the VarInt length prefix Fabric's payload codec put on the front.
     *
     * <p>Tolerant on purpose. If the prefix does not describe the rest of the buffer, the
     * message is assumed to be unframed and returned as-is. A future loader that frames
     * differently then still works rather than being silently dropped, which is the failure
     * this whole function exists to undo.
     *
     * @return the message body, with any length prefix stripped
     */
    private static byte[] stripLengthPrefix(byte[] message) {
        int value = 0;
        int shift = 0;
        int index = 0;
        while (index < message.length && shift <= 35) {
            byte b = message[index++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (value == message.length - index)
                        ? Arrays.copyOfRange(message, index, message.length)
                        : message;
            }
            shift += 7;
        }
        return message;
    }

    /** Put the prefix back, so the client's readByteArray() finds what it expects. */
    private static byte[] withLengthPrefix(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value = body.length;
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static boolean isOnline(UUID player) {
        Player found = Bukkit.getPlayer(player);
        return found != null && found.isOnline();
    }

    private static String addressOf(Player player) {
        InetSocketAddress address = player.getAddress();
        return (address != null && address.getAddress() != null)
                ? address.getAddress().getHostAddress()
                : "";
    }

    /**
     * Returns the address the backchannel binds to. Empty means every interface, exactly as the
     * game's own server.properties ip= does.
     *
     * @return the configured bind address, empty for every interface
     */
    private static String bindAddress(Plugin owner) {
        String ip = owner.getServer().getIp();
        return ip == null ? "" : ip;
    }
}
