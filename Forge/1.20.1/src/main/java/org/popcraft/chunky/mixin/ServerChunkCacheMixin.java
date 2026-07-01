package org.popcraft.chunky.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheMixin {
    @SuppressWarnings("UnnecessaryModifier")
    @Invoker("getChunkFutureMainThread")
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> invokeGetChunkFutureMainThread(final int chunkX,
                                                                                                                 final int chunkZ,
                                                                                                                 final ChunkStatus toStatus,
                                                                                                                 final boolean create);

    @Invoker
    boolean invokeRunDistanceManagerUpdates();
}
