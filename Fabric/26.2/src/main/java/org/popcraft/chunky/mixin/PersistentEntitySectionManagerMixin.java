package org.popcraft.chunky.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fixes the long-standing worldgen entity-retention defect: mobs (and other entities) that spawn
 * as chunks are generated during a large pre-generation are never unloaded, so server RAM climbs
 * until a restart, and a blocking save of the backlog can stall the main thread past the 60-second
 * tick watchdog and crash the server.
 *
 * <p>ROOT CAUSE (vanilla {@code PersistentEntitySectionManager}): worldgen entities enter via
 * {@code addWorldGenChunkEntities}, leaving the chunk's entity load-status {@code FRESH}.
 * {@code storeChunkSections} refuses to free a FRESH chunk's entities until an async disk-read
 * round-trip completes ({@code requestChunkLoad(); return false;}). That read exists only to MERGE
 * any already-persisted entities before the store overwrites the region entry. During heavy pre-gen
 * the world disk is saturated by chunk writes, so the unload reads stall, the chunks never finish
 * unloading, and entities pile up in {@code sectionStorage} until shutdown.
 *
 * <p>FIX: when the chunk has NO persisted entity data on disk there is nothing to merge, so the read
 * is pure overhead. We {@code @Redirect} the {@code loadEntities} call inside {@code requestChunkLoad}:
 * if the chunk's slot in the entity region's offset table is empty (or the region file does not
 * exist), we return an immediately-completed empty result instead of reading the disk. The chunk
 * resolves FRESH -> LOADED without I/O and vanilla's normal store+unload path then persists the
 * in-memory worldgen entities and frees them. When real on-disk data IS present (e.g. a re-gen over
 * chunks whose entity region was orphaned by a world-region prune), we fall through to the untouched
 * vanilla read+merge, so no entity is ever lost.
 *
 * <p>THREAD-SAFETY: {@code requestChunkLoad} runs on the server main thread. We never touch the
 * IO-thread-owned {@code RegionFileStorage} region cache; we read the region file's 4096-byte offset
 * table directly. A FRESH chunk's offset slot is provably 0 (a slot is only written when that chunk
 * is stored, at which point it is no longer FRESH), so even a cached/stale table yields a correct
 * "absent" verdict for fresh chunks - and any uncertainty falls back to the vanilla read.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {

    @Unique
    private static final int CHUNKY$REGION_CACHE_MAX = 256;

    @Unique
    private static final int[] CHUNKY$EMPTY_TABLE = new int[1024];

    // Bounded LRU of per-region offset tables. Accessed only on the server main thread
    // (requestChunkLoad runs there), so no synchronization is required.
    @Unique
    private final Map<Long, int[]> chunky$regionOffsets = new LinkedHashMap<Long, int[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, int[]> eldest) {
            return size() > CHUNKY$REGION_CACHE_MAX;
        }
    };

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "requestChunkLoad",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;loadEntities(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture chunky$skipReadForFreshChunks(final EntityPersistentStorage permanentStorage, final ChunkPos pos) {
        try {
            if (chunky$entitiesAbsentOnDisk(permanentStorage, pos)) {
                return CompletableFuture.completedFuture(new ChunkEntities(pos, List.of()));
            }
        } catch (final Throwable ignored) {
            // Any uncertainty -> the real vanilla load (never risk skipping genuine on-disk data).
        }
        return permanentStorage.loadEntities(pos);
    }

    @Unique
    private boolean chunky$entitiesAbsentOnDisk(final EntityPersistentStorage<?> permanentStorage, final ChunkPos pos) throws IOException {
        if (!(permanentStorage instanceof EntityStorage)) {
            return false;
        }
        final SimpleRegionStorage simpleRegionStorage = ((EntityStorageAccessor) permanentStorage).chunky$getSimpleRegionStorage();
        final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) simpleRegionStorage).chunky$getWorker();
        final RegionFileStorage storage = ((IOWorkerAccessor) (Object) worker).chunky$getStorage();
        final Path folder = ((RegionFileStorageAccessor) (Object) storage).chunky$getFolder();

        final int regionX = pos.getRegionX();
        final int regionZ = pos.getRegionZ();
        final long regionKey = (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);

        int[] table = chunky$regionOffsets.get(regionKey);
        if (table == null) {
            table = chunky$readOffsetTable(folder.resolve("r." + regionX + "." + regionZ + ".mca"));
            chunky$regionOffsets.put(regionKey, table);
        }
        final int index = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
        return table[index] == 0;
    }

    /**
     * Reads the 4096-byte chunk-offset table from the head of an Anvil region file. A non-existent
     * file means every chunk is absent; each offset int is 0 when that chunk has never been written.
     */
    @Unique
    private int[] chunky$readOffsetTable(final Path mca) throws IOException {
        if (!Files.exists(mca)) {
            return CHUNKY$EMPTY_TABLE;
        }
        final int[] table = new int[1024];
        try (FileChannel channel = FileChannel.open(mca, StandardOpenOption.READ)) {
            final ByteBuffer buffer = ByteBuffer.allocate(4096);
            final int read = channel.read(buffer, 0L);
            buffer.flip();
            final IntBuffer ints = buffer.asIntBuffer();
            final int count = Math.min(1024, Math.max(0, read) / 4);
            for (int i = 0; i < count; i++) {
                table[i] = ints.get(i);
            }
        }
        return table;
    }
}
