package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes IOWorker internals.
 * <p>
 * {@code pendingWrites} size is the count of chunk writes queued to disk but not yet
 * flushed - the deferred region-write backlog used for write-queue backpressure. Read
 * size() only (the map is mutated solely on the IO executor thread; iterating it
 * off-thread is unsafe, but a size() read is benign and at worst slightly stale).
 * On MC 1.21.1 {@code pendingWrites} is a plain {@link Map} (LinkedHashMap), NOT the
 * {@code SequencedMap} of 1.21.4+ - the field name is identical, only the declared type differs.
 * <p>
 * {@code storage} is the RegionFileStorage the worker writes through; the entity-unload
 * fix uses it only to resolve the region folder Path (read-only metadata).
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("pendingWrites")
    Map<?, ?> chunky$getPendingWrites();

    @Accessor("storage")
    RegionFileStorage chunky$getStorage();
}
