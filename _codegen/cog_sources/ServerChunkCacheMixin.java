package com.kishku7.chunksmith.mixin;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
//[[[cog
// import cog, compat
// if compat.has_broadcast_changed_chunks(mcver):
//     cog.outl("import net.minecraft.util.profiling.ProfilerFiller;")
//]]]
//[[[end]]]
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

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

    //[[[cog
    // import cog, compat
    // if compat.has_broadcast_changed_chunks(mcver):
    //     cog.outl("")
    //     cog.outl("    @Invoker")
    //     cog.outl("    void invokeBroadcastChangedChunks(ProfilerFiller arg);")
    //]]]
    //[[[end]]]
}
