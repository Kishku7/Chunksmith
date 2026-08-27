package com.kishku7.chunksmith.lod;

import com.kishku7.chunksmith.lod.net.CsLodControl;
import com.kishku7.chunksmith.lod.net.CsLodHttpServer;
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
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Serves the CSLOD store to clients from a Bukkit/Paper server.
 *
 * <p><b>Why this did not exist before.</b> The plugin has generated LOD data since 3.2.0, and every
 * piece of the machinery for SENDING it -- the wire format, the token store, the HTTP backchannel --
 * has shipped inside the plugin jar all along, because those classes live in {@code shared_common}.
 * What was missing was the twenty lines that connect them: nothing registered a channel, nothing
 * started the HTTP server, nothing answered a client's hello. So a player on a plugin server got
 * nothing, no configuration could change that, and the startup line did not say so
 * (mod_support #18). This is that missing connection.
 *
 * <p><b>What it deliberately does NOT do.</b> The in-band fallback -- streaming region data down the
 * plugin channel when the HTTP port is unreachable -- is not implemented here. On the mod that path
 * exists because a firewalled port must not mean no LOD at all; here, a client that cannot reach the
 * port simply gets nothing, and the log says which port it should have been able to reach. That is a
 * real limitation and it is written down rather than glossed: the fix for a blocked port is to open
 * it, or to set {@code lod-backchannel-port} to one the host does allow.
 *
 * <p><b>Dimension roots.</b> A mod-loader server keeps every dimension under one save root. Bukkit
 * gives each world its own folder, so this hands {@link CsLodHttpServer} a resolver rather than a
 * single path, and nobody's existing store has to move.
 */
public final class CsLodServerBukkit implements PluginMessageListener {

    private static final Logger LOGGER = Logger.getLogger("Chunksmith");
    private static final String CHANNEL = CsLodProtocol.NAMESPACE + ":" + CsLodProtocol.CHANNEL;

    private static final CsLodTokens TOKENS = new CsLodTokens();

    private static Plugin plugin;
    private static CsLodHttpServer http;
    private static CsLodServerBukkit listener;

    private CsLodServerBukkit() {
    }

    /** Called from {@code onEnable}, after the config exists. Safe to call when LOD is off. */
    public static void enable(final Plugin owner, final Config config) {
        plugin = owner;
        if (!LodSupport.lodEnabled(config)) {
            return;
        }

        listener = new CsLodServerBukkit();
        owner.getServer().getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
        owner.getServer().getMessenger().registerIncomingPluginChannel(owner, CHANNEL, listener);

        http = new CsLodHttpServer(CsLodServerBukkit::rootFor, TOKENS, CsLodServerBukkit::isOnline);
        final int bound = http.start(bindAddress(owner), owner.getServer().getPort(),
                config.getLodBackchannelPort());

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
        if (http != null) {
            http.stop();
            http = null;
        }
        TOKENS.clear();
    }

    /** A token must never outlive the session that earned it. Wired to PlayerQuitEvent. */
    public static void onQuit(final UUID player) {
        TOKENS.revoke(player);
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
        final Config config = com.kishku7.chunksmith.ChunksmithProvider.get().getConfig();
        if (!LodSupport.lodEnabled(config)) {
            return 0;
        }
        http = new CsLodHttpServer(CsLodServerBukkit::rootFor, TOKENS, CsLodServerBukkit::isOnline);
        final int bound = http.start(bindAddress(plugin), plugin.getServer().getPort(),
                config.getLodBackchannelPort());
        readvertise(bound);
        return bound;
    }

    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        // STRIP THE LENGTH PREFIX. Fabric frames the payload with buf.writeByteArray(), which is a
        // VarInt length followed by the bytes; the mod's receiver calls readByteArray() and never
        // sees it. Bukkit hands us the packet payload raw, so the prefix is still on the front and
        // the first byte is a length, not a message id.
        final byte[] body = stripLengthPrefix(message);
        if (body.length == 0) {
            return;
        }
        try (final DataInputStream in = CsLodMessages.reader(body)) {
            final byte id = in.readByte();
            if (id == CsLodProtocol.C2S_HELLO) {
                CsLodMessages.decodeClientHello(in);   // read it fully; we do not gate on the renderer
                hello(player);
            }
            // Every other client message is a request for IN-BAND data, which this platform does not
            // serve. Dropping it silently is correct: the client already falls back to "no data" when
            // no reply arrives, and answering with an error it has no handler for would be worse.
        } catch (final IOException e) {
            LOGGER.warning("Chunksmith: malformed LOD message from " + player.getName() + ": " + e);
        }
    }

    private static void hello(final Player player) {
        final List<String> dims = dimensions();
        LOGGER.info("Chunksmith: LOD hello from " + player.getName()
                + " -- " + dims.size() + " dimension(s) to offer");
        if (!player.getListeningPluginChannels().contains(CHANNEL)) {
            // Bukkit drops a send to a player that has not announced the channel, and it drops
            // it without a word. Saying so is the difference between a diagnosable problem and a
            // server that looks fine while delivering nothing.
            LOGGER.warning("Chunksmith: " + player.getName() + " spoke to us on " + CHANNEL
                    + " but has not registered that channel, so the reply cannot be delivered."
                    + " Channels it did register: " + player.getListeningPluginChannels());
        }
        final boolean available = !dims.isEmpty();
        final int port = http == null ? 0 : http.getPort();
        // No data or no port means no credential. A token that can fetch nothing is how an operator
        // ends up reading "1 live token, 0 files" and rightly wondering what it means.
        final String token = (available && port != 0)
                ? TOKENS.issue(player.getUniqueId(), addressOf(player))
                : "";
        try {
            player.sendPluginMessage(plugin, CHANNEL, withLengthPrefix(CsLodMessages.encode(
                    new CsLodMessages.ServerHello(CsLodProtocol.VERSION, available, port, token, dims))));
        } catch (final IOException e) {
            LOGGER.warning("Chunksmith: could not answer the LOD hello from "
                    + player.getName() + ": " + e);
        }
    }

    /** Re-tell every online player the new port, with a fresh token. Mirrors the mod's readvertise. */
    private static void readvertise(final int port) {
        int told = 0;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            hello(player);
            told++;
        }
        LOGGER.info("Chunksmith: LOD backchannel moved to port "
                + (port == 0 ? "none" : String.valueOf(port))
                + " -- " + told + " connected client(s) re-issued a token. No relog needed.");
    }

    /** Dimensions that actually have something to serve, by the same rule the mod uses. */
    private static List<String> dimensions() {
        final List<Path> dirs = new ArrayList<>();
        for (final World world : Bukkit.getWorlds()) {
            dirs.add(LodSupport.storeRoot(world));
        }
        return CsLodStoreScan.servable(dirs, System.currentTimeMillis());
    }

    /** Bukkit worlds do not share a parent, so each dimension resolves to its own world folder. */
    private static Path rootFor(final String dimension) {
        for (final World world : Bukkit.getWorlds()) {
            if (LodSupport.dimensionKey(world).equals(dimension)) {
                return LodSupport.storeRoot(world);
            }
        }
        return null;
    }

    /**
     * Remove the VarInt length prefix Fabric's payload codec put on the front.
     *
     * <p>Tolerant on purpose: if the prefix does not describe the rest of the buffer, the message is
     * assumed to be unframed and returned as-is. A future loader that frames differently then still
     * works rather than being silently dropped, which is the failure this whole function exists to
     * undo.
     */
    private static byte[] stripLengthPrefix(final byte[] message) {
        int value = 0;
        int shift = 0;
        int index = 0;
        while (index < message.length && shift <= 35) {
            final byte b = message[index++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (value == message.length - index)
                        ? java.util.Arrays.copyOfRange(message, index, message.length)
                        : message;
            }
            shift += 7;
        }
        return message;
    }

    /** Put the prefix back, so the client's readByteArray() finds what it expects. */
    private static byte[] withLengthPrefix(final byte[] body) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int value = body.length;
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static boolean isOnline(final UUID player) {
        final Player found = Bukkit.getPlayer(player);
        return found != null && found.isOnline();
    }

    private static String addressOf(final Player player) {
        final InetSocketAddress address = player.getAddress();
        return (address != null && address.getAddress() != null)
                ? address.getAddress().getHostAddress()
                : "";
    }

    /** Empty means every interface, exactly as the game's own server.properties ip= does. */
    private static String bindAddress(final Plugin owner) {
        final String ip = owner.getServer().getIp();
        return ip == null ? "" : ip;
    }
}
