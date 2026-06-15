package org.popcraft.chunky.mixin;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * 1.20.5/1.20.6 ADAPTATION: {@code getChunkFutureMainThread} returns
 * {@code CompletableFuture<ChunkResult<ChunkAccess>>} here. On 1.20.1 it returned
 * {@code CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>} - the
 * {@code Either} -> {@code ChunkResult} change landed in 1.20.2, so this is the only CS
 * mixin/accessor target that differs across the whole 1.20 line. The result is only chained
 * through {@code thenApplyAsync(Function.identity(), ...)} and discarded in {@code FabricWorld},
 * so only the declared type changes - no consumer logic does.
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheMixin {
    @SuppressWarnings("UnnecessaryModifier")
    @Invoker("getChunkFutureMainThread")
    public CompletableFuture<ChunkResult<ChunkAccess>> invokeGetChunkFutureMainThread(final int chunkX,
                                                                                      final int chunkZ,
                                                                                      final ChunkStatus toStatus,
                                                                                      final boolean create);

    @Invoker
    boolean invokeRunDistanceManagerUpdates();
}
