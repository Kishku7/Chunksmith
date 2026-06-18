package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes IOWorker internals.
 * <p>
 * {@code pendingWrites} size is the deferred region-write backlog used for write-queue backpressure.
 * On the 1.20.x line {@code pendingWrites} is a plain {@link Map} (LinkedHashMap), not the
 * {@code SequencedMap} of the 26.x line (which targets Java 21) - the field name is identical, only
 * the element type differs, and {@code Map} is the Java-17-safe declaration.
 * <p>
 * {@code storage} is the RegionFileStorage the worker writes through; the entity-unload fix uses it
 * only to resolve the entity-region folder Path (read-only metadata).
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("pendingWrites")
    Map<?, ?> chunky$getPendingWrites();

    @Accessor("storage")
    RegionFileStorage chunky$getStorage();
}
