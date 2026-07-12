package com.kishku7.chunksmith.lod.net;

import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends region files IN-BAND, a few slices per tick -- the fallback for a server with no open backchannel
 * port.
 *
 * <p><b>This path is slow on purpose.</b> It rides the SAME connection as gameplay, so every byte it sends
 * competes with movement, chunks and entities. The backchannel exists precisely so this is not the normal
 * path: it moves ~55 MB/s at network speed with the game connection untouched, while this dribbles along at
 * a few hundred KB/s. A player on this path waits, and that is the honest cost of not opening a port.
 *
 * <p>So the rule here is: <b>gameplay wins, LOD fills the gaps.</b> A hard cap of a few slices per tick,
 * never a burst, and the client can stop it at any moment.
 */
public final class CsLodInBandSender {

    /**
     * Bytes per slice. Comfortably inside the packet ceiling, and small enough that one slice is never a
     * spike in the middle of a tick.
     */
    private static final int SLICE_BYTES = 24 * 1024;

    /**
     * Slices per player per tick. 4 x 24 KB x 20 tps = ~1.9 MB/s -- fast enough to be useful, slow enough
     * that nobody's game stutters because someone else is fetching terrain.
     */
    private static final int SLICES_PER_TICK = 4;

    private static final Map<UUID, Deque<Slice>> QUEUES = new ConcurrentHashMap<>();

    private CsLodInBandSender() {
    }

    /** Queue a set of regions for a player. Replaces anything already queued for them. */
    public static void queue(final ServerPlayer player, final Path storeRoot, final String dimension,
                             final List<CsLodMessages.RegionEntry> wanted) throws IOException {
        final Deque<Slice> queue = new ArrayDeque<>();
        for (final CsLodMessages.RegionEntry entry : wanted) {
            final Path file = storeRoot.resolve(dimension)
                    .resolve("r." + entry.regionX() + "." + entry.regionZ() + ".cslod");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            final byte[] bytes = Files.readAllBytes(file);
            for (int offset = 0; offset < bytes.length; offset += SLICE_BYTES) {
                final int length = Math.min(SLICE_BYTES, bytes.length - offset);
                final byte[] slice = new byte[length];
                System.arraycopy(bytes, offset, slice, 0, length);
                final boolean last = offset + length >= bytes.length;
                queue.add(new Slice(dimension, entry.regionX(), entry.regionZ(), last, slice));
            }
        }
        QUEUES.put(player.getUUID(), queue);
    }

    /** Drip-feed. Call once per server tick. */
    public static void tick(final ServerPlayer player) {
        final Deque<Slice> queue = QUEUES.get(player.getUUID());
        if (queue == null) {
            return;
        }
        for (int i = 0; i < SLICES_PER_TICK; i++) {
            final Slice slice = queue.poll();
            if (slice == null) {
                QUEUES.remove(player.getUUID());
                CsLodServerNet.sendTo(player, CsLodMessages.done());
                return;
            }
            try {
                CsLodServerNet.sendTo(player, CsLodMessages.encode(new CsLodMessages.RegionSlice(
                        slice.dimension, slice.regionX, slice.regionZ, slice.last, slice.data)));
            } catch (final IOException e) {
                QUEUES.remove(player.getUUID());
                return;
            }
        }
    }

    /** The client asked us to stop. Stop. */
    public static void cancel(final ServerPlayer player) {
        QUEUES.remove(player.getUUID());
    }

    public static void forget(final UUID player) {
        QUEUES.remove(player);
    }

    /** Queued slices across all players -- for the status line. */
    public static int pending() {
        return QUEUES.values().stream().mapToInt(Deque::size).sum();
    }

    private record Slice(String dimension, int regionX, int regionZ, boolean last, byte[] data) {
    }
}
