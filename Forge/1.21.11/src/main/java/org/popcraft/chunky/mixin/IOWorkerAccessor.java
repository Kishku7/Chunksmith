package org.popcraft.chunky.mixin;

import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SequencedMap;

/**
 * Exposes IOWorker internals used by the worldgen entity-unload fix (MODERN: MC 1.21.4+).
 *
 * <p>{@code consecutiveExecutor} is the single-threaded executor that owns the worker's mutable state
 * (pendingWrites, the region-file cache). The fix submits its "does this chunk have persisted entity
 * data?" probe onto this executor so the check is serialized against in-flight stores instead of
 * racing the writer thread.
 *
 * <p>{@code pendingWrites} (a non-thread-safe SequencedMap mutated only on the executor thread) is
 * read via {@code containsKey} inside an executor task to catch entities persisted-but-not-yet-on-disk.
 *
 * <p>{@code storage} is the RegionFileStorage the worker writes through; the fix uses it only to
 * resolve the entity-region folder Path.
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("consecutiveExecutor")
    PriorityConsecutiveExecutor chunky$getConsecutiveExecutor();

    @Accessor("pendingWrites")
    SequencedMap<?, ?> chunky$getPendingWrites();

    @Accessor("storage")
    RegionFileStorage chunky$getStorage();
}
