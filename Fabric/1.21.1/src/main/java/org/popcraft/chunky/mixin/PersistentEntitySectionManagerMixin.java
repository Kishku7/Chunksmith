package org.popcraft.chunky.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.popcraft.chunky.util.Debug;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worldgen entity-retention fix - LEGACY/SRS variant (ProcessorMailbox + SimpleRegionStorage chain:
 * MC 1.20.6 and 1.21.1 - 1.21.3). Behaviourally identical to the modern variant; only the IOWorker
 * task-submit primitive differs - the pre-1.21.4 worker drains its single thread via a
 * {@code ProcessorMailbox} instead of a {@code PriorityConsecutiveExecutor}. We enqueue the
 * presence probe onto that mailbox (the one thread that owns pendingWrites and the region files) and
 * complete our own future, so the check observes a consistent snapshot - pendingWrites OR on-disk
 * offset table - with no writer race, no torn header, and no stale cache. Present-or-uncertain falls
 * through to the untouched vanilla read+merge: no entity is ever lost.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {

    @Unique private static final Logger CHUNKY$LOG = LogUtils.getLogger();
    @Unique private static final long CHUNKY$DIAG_INTERVAL_MS = 5000L;

    @Unique private final AtomicLong chunky$fastHits = new AtomicLong();
    @Unique private final AtomicLong chunky$vanillaFalls = new AtomicLong();
    @Unique private long chunky$lastDiag = 0L;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "requestChunkLoad",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;loadEntities(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture chunky$skipReadForFreshChunks(final EntityPersistentStorage permanentStorage, final ChunkPos pos) {
        if (!(permanentStorage instanceof EntityStorage)) {
            return permanentStorage.loadEntities(pos);
        }

        final ProcessorMailbox<StrictQueue.IntRunnable> mailbox;
        final Map<?, ?> pendingWrites;
        final Path entityRegionFolder;
        try {
            final SimpleRegionStorage simpleRegionStorage = ((EntityStorageAccessor) permanentStorage).chunky$getSimpleRegionStorage();
            final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) simpleRegionStorage).chunky$getWorker();
            final IOWorkerAccessor wa = (IOWorkerAccessor) (Object) worker;
            mailbox = wa.chunky$getMailbox();
            pendingWrites = wa.chunky$getPendingWrites();
            final RegionFileStorage storage = wa.chunky$getStorage();
            entityRegionFolder = ((RegionFileStorageAccessor) (Object) storage).chunky$getFolder();
        } catch (final Throwable introspectionFailed) {
            return permanentStorage.loadEntities(pos);
        }

        final CompletableFuture<Boolean> probe = new CompletableFuture<>();
        mailbox.tell(new StrictQueue.IntRunnable(0, () -> {
            boolean present;
            try {
                present = pendingWrites.containsKey(pos) || chunky$entitiesOnDisk(entityRegionFolder, pos);
            } catch (final Throwable uncertain) {
                present = true;
            }
            if (present) {
                this.chunky$vanillaFalls.incrementAndGet();
            } else {
                this.chunky$fastHits.incrementAndGet();
            }
            probe.complete(present);
        }));
        return probe.thenCompose(present -> present
                ? permanentStorage.loadEntities(pos)
                : CompletableFuture.completedFuture(new ChunkEntities(pos, List.of())));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void chunky$debugTick(final CallbackInfo ci) {
        if (!Debug.ENABLED) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - this.chunky$lastDiag < CHUNKY$DIAG_INTERVAL_MS) {
            return;
        }
        this.chunky$lastDiag = now;
        try {
            final String stats = ((PersistentEntitySectionManager) (Object) this).gatherStats();
            CHUNKY$LOG.info("[Chunksmith debug] fastHits={} vanillaFalls={} | known,visible,sections,loadStatuses,visibility,inbox,toUnload={}",
                    this.chunky$fastHits.get(), this.chunky$vanillaFalls.get(), stats);
        } catch (final Throwable ignored) {
        }
    }

    /**
     * True iff the entity region file exists AND its 4096-byte offset table marks this chunk present.
     * MUST run on the IOWorker mailbox thread so the read is serialized against region writes and
     * never creates a region file (Files.exists gate).
     */
    @Unique
    private static boolean chunky$entitiesOnDisk(final Path folder, final ChunkPos pos) throws IOException {
        final Path mca = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        if (!Files.exists(mca)) {
            return false;
        }
        final int slot = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
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
