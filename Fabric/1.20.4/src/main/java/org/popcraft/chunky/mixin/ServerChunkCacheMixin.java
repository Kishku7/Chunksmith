package org.popcraft.chunky.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * MC 1.20.2 - 1.20.4: {@code getChunkFutureMainThread} STILL returns
 * {@code CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>} - identical
 * to 1.20.1. VERIFIED against the official 1.20.4 Mojang server mappings: there is NO
 * {@code net.minecraft.server.level.ChunkResult} class at 1.20.4, and
 * {@code ChunkHolder.ChunkLoadingFailure} (the {@code Either} payload) still exists. The
 * {@code Either} -> {@code ChunkResult} rename actually landed at 1.20.5 (NOT 1.20.2 as the
 * Either/ChunkResult naming history is sometimes reported). {@code ChunkStatus} is likewise
 * still in the OLD package {@code net.minecraft.world.level.chunk} (the .status move was 1.20.5).
 * Net result: for CS's targets this variant is byte-identical to fabric-1.20.1 - the whole
 * 1.20.1 - 1.20.4 range shares one mixin shape, and the only real CS break across the 1.20
 * line is at 1.20.5 (ChunkResult + ChunkStatus package move + JDK21).
 */
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
