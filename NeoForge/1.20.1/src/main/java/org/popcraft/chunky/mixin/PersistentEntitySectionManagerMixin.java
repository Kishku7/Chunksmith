package org.popcraft.chunky.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.popcraft.chunky.util.Debug;
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
 * Worldgen entity-retention fix - PRE-1.21.11 STRUCTURE variant (MC 1.21.1 - 1.21.10).
 *
 * <p>Identical in behaviour to the 1.21.11/26.x version, but the entity-region folder is reached
 * through {@code EntityStorage.worker} directly (the IOWorker the EntityStorage owns), because the
 * {@code SimpleRegionStorage} layer does not exist before 1.21.11. Everything else - the
 * {@code requestChunkLoad} -> {@code loadEntities} redirect that skips the redundant disk read for
 * chunks with no persisted entities, the offset-table probe, and the {@code /cs debug}-gated
 * telemetry - is the same.
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

    @Unique
    private final Map<Long, int[]> chunky$regionOffsets = new LinkedHashMap<Long, int[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, int[]> eldest) {
            return size() > CHUNKY$REGION_CACHE_MAX;
        }
    };

    @Unique private long chunky$fastHits = 0L;
    @Unique private long chunky$vanillaFalls = 0L;
    @Unique private long chunky$lastDiag = 0L;

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
                this.chunky$fastHits++;
                return CompletableFuture.completedFuture(new ChunkEntities(pos, List.of()));
            }
        } catch (final Throwable ignored) {
            // Any uncertainty -> the real vanilla load (never risk skipping genuine on-disk data).
        }
        this.chunky$vanillaFalls++;
        return permanentStorage.loadEntities(pos);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void chunky$debugTick(final CallbackInfo ci) {
        if (!Debug.ENABLED) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - this.chunky$lastDiag < CHUNKY$DIAG_INTERVAL_MS) {
            return;
        }
        this.chunky$lastDiag = now;
        try {
            final String stats = ((PersistentEntitySectionManager) (Object) this).gatherStats();
            CHUNKY$LOG.info("[Chunksmith debug] fastHits={} vanillaFalls={} | known,visible,sections,loadStatuses,visibility,inbox,toUnload={}",
                    this.chunky$fastHits, this.chunky$vanillaFalls, stats);
        } catch (final Throwable ignored) {
        }
    }

    @Unique
    private boolean chunky$entitiesAbsentOnDisk(final EntityPersistentStorage<?> permanentStorage, final ChunkPos pos) throws IOException {
        if (!(permanentStorage instanceof EntityStorage)) {
            return false;
        }
        final IOWorker worker = ((EntityStorageAccessor) permanentStorage).chunky$getEntityWorker();
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