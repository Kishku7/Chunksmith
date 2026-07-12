package com.kishku7.chunksmith.lod.net;

import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
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

    private static final Map<UUID, Transfer> TRANSFERS = new ConcurrentHashMap<>();

    private CsLodInBandSender() {
    }

    /**
     * Queue a set of regions for a player. Replaces anything already queued for them.
     *
     * <p><b>Nothing is read here.</b> Only the file LIST is captured; the bytes are pulled a slice at a
     * time in {@link #tick}, straight off disk. The obvious implementation -- slurp every wanted region
     * with {@code readAllBytes} and pre-slice it -- put the ENTIRE requested set on the heap, on the
     * SERVER THREAD, before a single byte went out: a legitimate client asking for a few hundred region
     * files (a normal first join against a pregenerated world) is hundreds of megabytes and a multi-second
     * main-thread stall, and a hostile one is a free OOM. A cursor costs one open file per player instead.
     */
    public static void queue(final ServerPlayer player, final Path storeRoot, final String dimension,
                             final List<CsLodMessages.RegionEntry> wanted) throws IOException {
        cancel(player);
        final Deque<Region> regions = new ArrayDeque<>();
        for (final CsLodMessages.RegionEntry entry : wanted) {
            final Path file = storeRoot.resolve(dimension)
                    .resolve("r." + entry.regionX() + "." + entry.regionZ() + ".cslod");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            regions.add(new Region(entry.regionX(), entry.regionZ(), file, Files.size(file)));
        }
        TRANSFERS.put(player.getUUID(), new Transfer(dimension, regions));
    }

    /** Drip-feed. Call once per server tick. */
    public static void tick(final ServerPlayer player) {
        final Transfer transfer = TRANSFERS.get(player.getUUID());
        if (transfer == null) {
            return;
        }
        try {
            for (int i = 0; i < SLICES_PER_TICK; i++) {
                if (!transfer.sendNext(player)) {
                    finish(player.getUUID(), transfer);
                    CsLodServerNet.sendTo(player, CsLodMessages.done());
                    return;
                }
            }
        } catch (final IOException e) {
            // A region vanished or the disk complained. Drop the transfer -- the client re-asks.
            finish(player.getUUID(), transfer);
        }
    }

    /** The client asked us to stop. Stop. */
    public static void cancel(final ServerPlayer player) {
        forget(player.getUUID());
    }

    public static void forget(final UUID player) {
        final Transfer transfer = TRANSFERS.get(player);
        if (transfer != null) {
            finish(player, transfer);
        }
    }

    private static void finish(final UUID player, final Transfer transfer) {
        TRANSFERS.remove(player, transfer);
        transfer.close();
    }

    /** Region files still to send across all players -- for the status line. */
    public static int pending() {
        return TRANSFERS.values().stream().mapToInt(Transfer::remaining).sum();
    }

    /** One region file still owed to a player. */
    private record Region(int regionX, int regionZ, Path file, long size) {
    }

    /**
     * A player's in-flight transfer: the regions still owed, plus a cursor into the one currently being
     * sent. Touched only from the server thread (queue/tick/cancel all run there).
     */
    private static final class Transfer {

        private final String dimension;
        private final Deque<Region> pending;

        private Region current;
        private InputStream in;
        private long sent;

        private Transfer(final String dimension, final Deque<Region> pending) {
            this.dimension = dimension;
            this.pending = pending;
        }

        /** Send at most one slice. Returns false when there is nothing left to send. */
        private boolean sendNext(final ServerPlayer player) throws IOException {
            if (this.in == null) {
                this.current = this.pending.poll();
                if (this.current == null) {
                    return false;
                }
                this.in = Files.newInputStream(this.current.file());
                this.sent = 0L;
            }

            final byte[] buffer = new byte[SLICE_BYTES];
            // readNBytes only stops short at EOF, so a short read IS the end of the file -- which also
            // covers a region the writer thread replaced underneath us.
            final int read = this.in.readNBytes(buffer, 0, SLICE_BYTES);
            if (read <= 0) {
                closeCurrent();
                return true;
            }
            this.sent += read;
            final boolean last = read < SLICE_BYTES || this.sent >= this.current.size();
            final byte[] data = read == SLICE_BYTES ? buffer : Arrays.copyOf(buffer, read);

            CsLodServerNet.sendTo(player, CsLodMessages.encode(new CsLodMessages.RegionSlice(
                    this.dimension, this.current.regionX(), this.current.regionZ(), last, data)));

            if (last) {
                closeCurrent();
            }
            return true;
        }

        private int remaining() {
            return this.pending.size() + (this.current == null ? 0 : 1);
        }

        private void closeCurrent() {
            if (this.in != null) {
                try {
                    this.in.close();
                } catch (final IOException ignored) {
                    // Closing a read-only stream. Nothing useful to do, and nothing at stake.
                }
                this.in = null;
            }
            this.current = null;
        }

        private void close() {
            this.pending.clear();
            closeCurrent();
        }
    }
}
