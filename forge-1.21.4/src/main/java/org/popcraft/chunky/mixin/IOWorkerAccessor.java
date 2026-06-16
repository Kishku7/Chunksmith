package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SequencedMap;

/**
 * Exposes the IOWorker's pending-write map. Its size is the count of chunk writes queued
 * to disk but not yet flushed — the deferred region-write backlog used for backpressure.
 * Read size() only (the map is mutated solely on the IO executor thread; iterating it
 * off-thread is unsafe, but a size() read is benign and at worst slightly stale).
 * <p>
 * 1.21.2 ADAPTATION: from 1.21.2 the {@code pendingWrites} field type changed from a plain
 * {@link java.util.Map} (LinkedHashMap, 1.21.1 and the whole 1.20 line) to a
 * {@link SequencedMap}. The mixin @Accessor target locator matches on the field descriptor,
 * so the accessor return type must be {@code SequencedMap} here (a {@code Map}-typed accessor
 * fails to bind against a {@code SequencedMap} field). Only size() is read, so the element
 * type is irrelevant.
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("pendingWrites")
    SequencedMap<?, ?> chunky$getPendingWrites();
}
