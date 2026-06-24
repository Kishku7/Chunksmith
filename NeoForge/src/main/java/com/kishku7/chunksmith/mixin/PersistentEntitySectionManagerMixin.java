package com.kishku7.chunksmith.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import com.kishku7.chunksmith.util.Debug;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * Fixes the long-standing worldgen entity-retention defect: mobs (and other entities) that spawn as
 * chunks are generated during a large pre-generation are never unloaded, so server RAM climbs until a
 * restart, and a blocking save of the backlog can stall the main thread past the 60-second tick
 * watchdog and crash the server.
 *
 * <p>ROOT CAUSE (vanilla {@code PersistentEntitySectionManager}): worldgen entities enter via
 * {@code addWorldGenChunkEntities}, leaving the chunk's entity load-status {@code FRESH}.
 * {@code storeChunkSections} refuses to free a FRESH chunk's entities until an async disk-read
 * round-trip completes ({@code requestChunkLoad(); return false;}). That read exists only to MERGE any
 * already-persisted entities before the store overwrites the region entry. During heavy pre-gen the
 * world disk is saturated by chunk writes, so the unload reads stall and entities pile up.
 *
 * <p>FIX: when the chunk has NO persisted entity data on disk there is nothing to merge, so the read
 * is pure overhead. We {@code @Redirect} the {@code loadEntities} call inside {@code requestChunkLoad}:
 * if the chunk's slot in the entity region's offset table is empty (or the region file does not exist),
 * we return an immediately-completed empty result instead of reading the disk. When real on-disk data
 * IS present we fall through to the untouched vanilla read+merge, so no entity is ever lost.
 *
 * <p>THREAD-SAFETY: {@code requestChunkLoad} runs on the server main thread; we read the region file's
 * 4096-byte offset table directly and never touch the IO-thread-owned region cache. A FRESH chunk's
 * slot is provably 0, so a cached/stale table still yields a correct "absent" verdict, and any
 * uncertainty falls back to the vanilla read.
 *
 * <p>DIAGNOSTICS: the {@code @Inject} into {@code tick} is gated behind {@link Debug#ENABLED} (toggled
 * by {@code /cs debug}); default OFF means it is a no-op and emits nothing.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {

    @Unique
    private static final int CHUNKY$REGION_CACHE_MAX = 256;

    @Unique
    private static final int[] CHUNKY$EMPTY_TABLE = new int[1024];

    @Unique
    private static final Logger CHUNKY$LOG = LogUtils.getLogger();

    @Unique
    private static final long CHUNKY$DIAG_INTERVAL_MS = 5000L;

    // Accessed only on the server main thread (requestChunkLoad / tick run there) - no sync needed.
    @Unique
    private final Map<Long, int[]> chunksmith$regionOffsets = new LinkedHashMap<Long, int[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, int[]> eldest) {
            return size() > CHUNKY$REGION_CACHE_MAX;
        }
    };

    @Unique private long chunksmith$fastHits = 0L;
    @Unique private long chunksmith$vanillaFalls = 0L;
    @Unique private long chunksmith$lastDiag = 0L;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "requestChunkLoad",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;loadEntities(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture chunksmith$skipReadForFreshChunks(final EntityPersistentStorage permanentStorage, final ChunkPos pos) {
        try {
            if (chunksmith$entitiesAbsentOnDisk(permanentStorage, pos)) {
                this.chunksmith$fastHits++;
                return CompletableFuture.completedFuture(new ChunkEntities(pos, List.of()));
            }
        } catch (final Throwable ignored) {
            // Any uncertainty -> the real vanilla load (never risk skipping genuine on-disk data).
        }
        this.chunksmith$vanillaFalls++;
        return permanentStorage.loadEntities(pos);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void chunksmith$debugTick(final CallbackInfo ci) {
        if (!Debug.ENABLED) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - this.chunksmith$lastDiag < CHUNKY$DIAG_INTERVAL_MS) {
            return;
        }
        this.chunksmith$lastDiag = now;
        try {
            // gatherStats() CSV: known,visible,sections,loadStatuses,visibility,inbox,toUnload
            final String stats = ((PersistentEntitySectionManager) (Object) this).gatherStats();
            CHUNKY$LOG.info("[Chunksmith debug] fastHits={} vanillaFalls={} | known,visible,sections,loadStatuses,visibility,inbox,toUnload={}",
                    this.chunksmith$fastHits, this.chunksmith$vanillaFalls, stats);
        } catch (final Throwable ignored) {
        }
    }

    @Unique
    private boolean chunksmith$entitiesAbsentOnDisk(final EntityPersistentStorage<?> permanentStorage, final ChunkPos pos) throws IOException {
        if (!(permanentStorage instanceof EntityStorage)) {
            return false;
        }
        final SimpleRegionStorage simpleRegionStorage = ((EntityStorageAccessor) permanentStorage).chunksmith$getSimpleRegionStorage();
        final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) simpleRegionStorage).chunksmith$getWorker();
        final RegionFileStorage storage = ((IOWorkerAccessor) (Object) worker).chunksmith$getStorage();
        final Path folder = ((RegionFileStorageAccessor) (Object) storage).chunksmith$getFolder();

        final int regionX = pos.getRegionX();
        final int regionZ = pos.getRegionZ();
        final long regionKey = (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);

        int[] table = chunksmith$regionOffsets.get(regionKey);
        if (table == null) {
            table = chunksmith$readOffsetTable(folder.resolve("r." + regionX + "." + regionZ + ".mca"));
            chunksmith$regionOffsets.put(regionKey, table);
        }
        final int index = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
        return table[index] == 0;
    }

    @Unique
    private int[] chunksmith$readOffsetTable(final Path mca) throws IOException {
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