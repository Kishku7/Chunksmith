package org.popcraft.chunky.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import org.popcraft.chunky.ChunkyFabric;
import org.popcraft.chunky.ducks.MinecraftServerExtension;
import org.popcraft.chunky.mixin.ChunkMapMixin;
import org.popcraft.chunky.mixin.SimpleRegionStorageAccessor;
import org.popcraft.chunky.mixin.IOWorkerAccessor;
import org.popcraft.chunky.mixin.ServerChunkCacheMixin;
import org.popcraft.chunky.platform.util.Location;
import org.popcraft.chunky.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World {
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().identifier().toString();
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
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
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
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = ChunkyFabric.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
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
        final LevelData.RespawnData respawn = world.getRespawnData();
        final BlockPos pos = respawn.pos();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), respawn.yaw(), respawn.pitch());
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
            while (pos.getY() > world.getMinY()) {
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
        final Identifier soundId = Identifier.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
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
            // 1.21.11: ChunkMap extends SimpleRegionStorage (ChunkStorage removed), which holds the IOWorker.
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) chunkMap).chunky$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunky$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
