package com.kishku7.chunksmith.mixin;

import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SequencedMap;

/**
 * Exposes IOWorker internals used by the worldgen entity-unload fix.
 *
 * <p>{@code consecutiveExecutor} is the single-threaded executor that owns ALL of the worker's mutable
 * state (pendingWrites, the RegionFileStorage region cache). The entity-unload fix submits its "does
 * this chunk have persisted entity data?" probe onto this executor so the check observes a consistent
 * snapshot -- serialized against in-flight stores -- instead of racing the writer thread.
 *
 * <p>{@code pendingWrites} holds chunk writes queued-but-not-yet-flushed. It is a plain
 * (non-thread-safe) map mutated solely on the executor thread; only ever touch it from inside an
 * executor task. The fix calls {@code containsKey} there to catch entities that are persisted-but-not-
 * yet-on-disk (the case a raw region-file read would miss). Its {@code size()} is also read elsewhere
 * for write backpressure.
 *
 * <p>{@code storage} is the RegionFileStorage the worker writes through; the fix uses it only to
 * resolve the entity-region folder Path (read-only metadata).
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("consecutiveExecutor")
    PriorityConsecutiveExecutor chunksmith$getConsecutiveExecutor();

    @Accessor("pendingWrites")
    SequencedMap<?, ?> chunksmith$getPendingWrites();

    @Accessor("storage")
    RegionFileStorage chunksmith$getStorage();
}
