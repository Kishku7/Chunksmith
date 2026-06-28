package com.kishku7.chunksmith.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import com.kishku7.chunksmith.util.Debug;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixes the long-standing worldgen entity-retention defect: mobs (and other entities) that spawn as
 * chunks are generated during a large pre-generation are never unloaded, so server RAM climbs until a
 * restart, and a blocking save of the backlog can stall the main thread past the 60-second tick
 * watchdog and crash the server.
 *
 * <p>ROOT CAUSE (vanilla {@code PersistentEntitySectionManager}): worldgen entities enter via
 * {@code addWorldGenChunkEntities}, leaving the chunk's entity load-status {@code FRESH}.
 * {@code storeChunkSections} refuses to free a FRESH chunk's entities until an async disk-read
 * round-trip completes ({@code requestChunkLoad(); return false;}). That read exists only to MERGE any
 * already-persisted entities before the store overwrites the region entry. During heavy pre-gen the
 * world disk is saturated by chunk writes, so the unload reads stall and entities pile up.
 *
 * <p>FIX: when the chunk has NO persisted entity data there is nothing to merge, so the read is pure
 * overhead. We {@code @Redirect} the {@code loadEntities} call inside {@code requestChunkLoad} and
 * decide whether real data exists. CORRECTNESS HINGES on making that decision against a consistent
 * view, so the probe runs on the IOWorker's own single-threaded executor -- the one thread that mutates
 * {@code pendingWrites} and owns the region files. There it checks BOTH:
 * <ul>
 *   <li>{@code pendingWrites} -- entities already stored this session but not yet flushed to disk; a
 *       plain on-disk read would miss these and the next store would clobber them, and</li>
 *   <li>the entity region file's 4096-byte offset table (read directly, serialized against writes, so
 *       it cannot tear, and gated by {@code Files.exists} so it never opens/creates a RegionFile).</li>
 * </ul>
 * If either says data is present -- or anything is uncertain -- we fall through to the untouched vanilla
 * read+merge, so no entity is ever lost. Only a provably-empty chunk skips the read (returns an
 * immediately-completed empty result), which is where the RAM/stall win comes from.
 *
 * <p>HISTORY: an earlier version made this decision on the server main thread using a directly-read,
 * never-invalidated offset-table cache. That raced the writer (torn header reads), went stale (a region
 * written after caching still read as "empty"), and ignored {@code pendingWrites} entirely -- so a
 * store/unload/re-load cycle within the IOWorker flush window could drop persisted entities. Moving the
 * check onto the IOWorker thread and dropping the cache closes all three holes.
 *
 * <p>DIAGNOSTICS: the {@code @Inject} into {@code tick} is gated behind {@link Debug#ENABLED} (toggled
 * by {@code /cs debug}); default OFF means it is a no-op and emits nothing.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {

    @Unique
    private static final Logger CHUNKY$LOG = LogUtils.getLogger();

    @Unique
    private static final long CHUNKY$DIAG_INTERVAL_MS = 5000L;

    // Diagnostic counters only. Touched from the IOWorker thread (the probe) and read from the main
    // thread (the debug tick), so use atomics to avoid a torn long even though the values are advisory.
    @Unique private final AtomicLong chunksmith$fastHits = new AtomicLong();
    @Unique private final AtomicLong chunksmith$vanillaFalls = new AtomicLong();
    @Unique private long chunksmith$lastDiag = 0L;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "requestChunkLoad",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;loadEntities(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture chunksmith$skipReadForFreshChunks(final EntityPersistentStorage permanentStorage, final ChunkPos pos) {
        if (!(permanentStorage instanceof EntityStorage)) {
            return permanentStorage.loadEntities(pos);
        }

        final PriorityConsecutiveExecutor executor;
        final SequencedMap<?, ?> pendingWrites;
        final Path entityRegionFolder;
        try {
            final SimpleRegionStorage simpleRegionStorage = ((EntityStorageAccessor) permanentStorage).chunksmith$getSimpleRegionStorage();
            final IOWorkerAccessor worker = (IOWorkerAccessor) (Object) ((SimpleRegionStorageAccessor) (Object) simpleRegionStorage).chunksmith$getWorker();
            executor = worker.chunksmith$getConsecutiveExecutor();
            pendingWrites = worker.chunksmith$getPendingWrites();
            final RegionFileStorage storage = worker.chunksmith$getStorage();
            entityRegionFolder = ((RegionFileStorageAccessor) (Object) storage).chunksmith$getFolder();
        } catch (final Throwable introspectionFailed) {
            // Cannot reach the worker internals -> never gamble, do the real vanilla load.
            return permanentStorage.loadEntities(pos);
        }

        // Decide on the worker's own single thread, where pendingWrites and the region files are
        // quiescent. "Present" means entity data exists either queued in pendingWrites or on disk; any
        // doubt resolves to present so we never skip a merge that was actually needed.
        return executor.<Boolean>scheduleWithResult(0, (final CompletableFuture<Boolean> result) -> {
            boolean present;
            try {
                present = pendingWrites.containsKey(pos) || chunksmith$entitiesOnDisk(entityRegionFolder, pos);
            } catch (final Throwable uncertain) {
                present = true;
            }
            if (present) {
                this.chunksmith$vanillaFalls.incrementAndGet();
            } else {
                this.chunksmith$fastHits.incrementAndGet();
            }
            result.complete(present);
        }).thenCompose(present -> present
                ? permanentStorage.loadEntities(pos)
                : CompletableFuture.completedFuture(new ChunkEntities(pos, List.of())));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void chunksmith$debugTick(final CallbackInfo ci) {
        if (!Debug.ENABLED) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - this.chunksmith$lastDiag < CHUNKY$DIAG_INTERVAL_MS) {
            return;
        }
        this.chunksmith$lastDiag = now;
        try {
            // gatherStats() CSV: known,visible,sections,loadStatuses,visibility,inbox,toUnload
            final String stats = ((PersistentEntitySectionManager) (Object) this).gatherStats();
            CHUNKY$LOG.info("[Chunksmith debug] fastHits={} vanillaFalls={} | known,visible,sections,loadStatuses,visibility,inbox,toUnload={}",
                    this.chunksmith$fastHits.get(), this.chunksmith$vanillaFalls.get(), stats);
        } catch (final Throwable ignored) {
        }
    }

    /**
     * True iff the entity region file for this chunk exists AND its 4096-byte offset table marks this
     * chunk's slot as present. MUST be called on the IOWorker executor thread so the read is serialized
     * against region writes (no torn header) and never creates a region file (Files.exists gate).
     */
    @Unique
    private static boolean chunksmith$entitiesOnDisk(final Path folder, final ChunkPos pos) throws IOException {
        final Path mca = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        if (!Files.exists(mca)) {
            return false;
        }
        final int slot = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32; // 0..1023
        try (FileChannel channel = FileChannel.open(mca, StandardOpenOption.READ)) {
            final ByteBuffer header = ByteBuffer.allocate(4096);
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    break;
                }
            }
            if (header.position() < (slot + 1) * 4) {
                return false;
            }
            return header.getInt(slot * 4) != 0;
        }
    }
}
