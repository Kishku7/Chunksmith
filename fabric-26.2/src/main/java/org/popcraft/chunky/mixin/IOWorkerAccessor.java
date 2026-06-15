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
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("pendingWrites")
    SequencedMap<?, ?> chunky$getPendingWrites();
}
