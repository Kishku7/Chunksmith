package org.popcraft.chunky.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.popcraft.chunky.ChunkyForge;
import org.popcraft.chunky.ducks.MinecraftServerExtension;
import org.popcraft.chunky.mixin.ChunkMapMixin;
import org.popcraft.chunky.mixin.ChunkStorageAccessor;
import org.popcraft.chunky.mixin.IOWorkerAccessor;
import org.popcraft.chunky.mixin.ServerChunkCacheMixin;
import org.popcraft.chunky.platform.util.Location;
import org.popcraft.chunky.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = ChunkyForge.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunky$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunky$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunky$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
