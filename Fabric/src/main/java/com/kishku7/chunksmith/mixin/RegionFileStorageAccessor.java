package com.kishku7.chunksmith.mixin;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

/**
 * Exposes the on-disk region folder. RegionFileStorage is final, so callers must
 * cast through (Object). The folder is read-only metadata; resolving a region file
 * path from it and reading the 4096-byte offset table directly lets the entity-unload
 * fix determine "does this chunk have persisted entities?" without going through the
 * IO executor or touching the (IO-thread-owned, non-thread-safe) RegionFile cache.
 */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {
    @Accessor("folder")
    Path chunky$getFolder();
}
