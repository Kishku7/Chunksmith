package org.popcraft.chunky.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@link IOWorker} that backs a chunk/region storage.
 * <p>
 * 1.21.11 ADAPTATION: the older {@code ChunkStorage} base class was removed at 1.21.11 and
 * {@code ChunkMap} now extends {@link SimpleRegionStorage} (the same rename the 26.x line used),
 * which holds the {@code IOWorker worker}. Casting a ChunkMap to this accessor yields its worker.
 * This replaces the 1.21.1-1.21.10 {@code ChunkStorageAccessor}.
 */
@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {
    @Accessor("worker")
    IOWorker chunky$getWorker();
}