package com.kishku7.chunksmith.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.util.UnloadDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.HashMap;
import java.util.Map;

/**
 * Ticket-level diagnostics -- PRESENCE-GATED to versions that actually have a TicketStorage.
 *
 * <p>Split out of {@code MinecraftServerMixin} on 2026-08-20. It was written straight against the
 * live server that had the residency bug, against {@code net.minecraft.world.level.TicketStorage}
 * and {@code net.minecraft.world.level.chunk.status.ChunkStatus} -- both of which are 1.21.11-and-newer
 * shapes. Sitting in the shared mixin it broke the build of EVERY older cell, which is how the whole
 * 3.5-3.13 line ended up with no jars outside 1.21.11 and therefore no CRITICAL smoketest coverage.
 *
 * <p>{@code compat.has_ticket_storage()} decides whether this file is generated at all, so on older
 * versions the class does not exist rather than existing as a stub -- the same compile-time-absent
 * seam {@code ChunkStorageAccessor} uses.
 *
 * <p>Diagnostics only: it reads and reports, it never mutates a ticket. The one thing here that DID
 * mutate -- the stale-ticket purge -- was deleted the same day, because a controlled A/B showed it
 * changed residency by 0.1 percent while evicting over ten thousand tickets.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTicketsMixin {

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    /** When the ticket-level histogram was last walked. It is O(resident), so not every tick. */
    @Unique
    private long chunksmith$lastLevelSampleMillis = 0L;

    /** How often to walk it. Ten seconds of a number that moves slowly is plenty. */
    @Unique
    private static final long CHUNKSMITH$LEVEL_SAMPLE_INTERVAL_MS = 10_000L;

    // No Cog here on purpose: this file lives in cog_sources/shared/ and is copied VERBATIM, so
    // it must not carry generator directives. It is gated to modern_11plus, and
    // compat.housekeeping_inject_at() returns TAIL for every version in that era, so the
    // injection point is not a drift point WITHIN this file's supported range.
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void chunksmith$onTicketDiagnosticsHook(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunksmith$sampleChunkLevels();
    }

    /**
     * Bucket every resident chunk by its ticket level.
     *
     * <p>The last question standing. Chunksmith's own ledger has ruled our tickets out -- a few
     * hundred outstanding while eleven thousand chunks sat resident -- so either something ELSE holds
     * these chunks at a live level, or nothing holds them and vanilla is simply not dropping them.
     * The level is what decides, and {@code DistanceManager.getChunkLevel} is public on every
     * supported version, so this needs no new plumbing beyond the resident map itself.
     *
     * <p>Thresholds follow {@code ChunkLevel}: 33 is FULL, and 44 and above is past the maximum a
     * loaded chunk may have, which is precisely "nothing wants this".
     */
    @Unique
    private void chunksmith$sampleChunkLevels() {
        final long now = System.currentTimeMillis();
        if (now - this.chunksmith$lastLevelSampleMillis < CHUNKSMITH$LEVEL_SAMPLE_INTERVAL_MS) {
            return;
        }
        this.chunksmith$lastLevelSampleMillis = now;
        long ticking = 0L;
        long loadedLevel = 0L;
        long droppable = 0L;
        final StringBuilder sample = new StringBuilder();
        int sampled = 0;
        // Tally every ticket on every resident chunk BY TYPE. Six sampled strings named the suspect;
        // this counts it. "How many" is the difference between a clue and a cause.
        final Map<String, Integer> byType = new HashMap<>();
        for (ServerLevel level : this.getAllLevels()) {
            final ChunkMap map = level.getChunkSource().chunkMap;
            final DistanceManager distance = map.getDistanceManager();
            final TicketStorage store =
                    ((DistanceManagerMixin) distance).getTicketStorage();
            for (final long pos : ((ChunkMapMixin) map).getVisibleChunkMap().keySet()) {
                for (final Ticket ticket : store.getTickets(pos)) {
                    byType.merge(String.valueOf(ticket.getType()), 1, Integer::sum);
                }
                // Buckets taken from ChunkLevel itself, NOT from hand-written numbers. The first
                // version of this used 33 and 44 from memory; MAX_LEVEL is actually
                // 33 + RADIUS_AROUND_FULL_CHUNK, so "44" was wrong and two whole levels of droppable
                // chunks were being counted as loaded. Reading the constant also makes the buckets
                // correct on every MC version instead of just the one they were guessed for.
                final int chunkLevel = distance.getChunkLevel(pos, false);
                if (chunkLevel <= ChunkLevel.byStatus(
                        ChunkStatus.FULL)) {
                    ticking++;
                    // Read the ticket rather than guess at it. A handful is enough to name a type.
                    if (sampled < 6) {
                        sampled++;
                        if (sample.length() > 0) {
                            sample.append(" | ");
                        }
                        // NOT new ChunkPos(long): on the 26 line ChunkPos became a RECORD and lost
                        // that constructor, which broke the whole 26 cell. The packed layout is
                        // stable everywhere we support (x in the low int, z in the high int -- see
                        // ChunkPos.asLong), and this is a DEBUG STRING, so decoding it here is both
                        // version-proof and cheaper than a drift helper for one log line.
                        sample.append('[').append((int) pos).append(", ").append((int) (pos >> 32))
                                .append(']')
                                .append(" lvl=").append(chunkLevel)
                                .append(" load=").append(store.getTicketDebugString(pos, false))
                                .append(" sim=").append(store.getTicketDebugString(pos, true));
                    }
                } else if (ChunkLevel.isLoaded(chunkLevel)) {
                    // 34..MAX_LEVEL: not accessible, but still held as worldgen CONTEXT for a FULL
                    // chunk nearby. A pre-gen inherently keeps this ring around its whole frontier.
                    loadedLevel++;
                } else {
                    droppable++;
                }
            }
        }
        UnloadDiagnostics.reportLevels(ticking, loadedLevel, droppable, now);
        UnloadDiagnostics.reportTicketSample(sampled == 0 ? "no chunk at a ticking level" : sample.toString());
        final StringBuilder tally = new StringBuilder();
        byType.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(6)
                .forEach(e -> {
                    if (tally.length() > 0) {
                        tally.append(", ");
                    }
                    tally.append(e.getKey()).append('=').append(e.getValue());
                });
        UnloadDiagnostics.reportTicketTally(tally.length() == 0 ? "no tickets on resident chunks" : tally.toString());
    }
}
