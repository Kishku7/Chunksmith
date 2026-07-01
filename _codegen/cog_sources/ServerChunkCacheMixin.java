package com.kishku7.chunksmith.mixin;

import net.minecraft.server.level.ServerChunkCache;
//[[[cog
// import cog, compat
// # ChunkResult (transitional+) vs Either + ChunkHolder (ancient) return-type imports.
// for imp in compat.chunk_future_result_imports(mcver):
//     cog.outl(imp)
// # ProfilerFiller only needed for the 26-only broadcast invoker.
// if compat.has_broadcast_changed_chunks(mcver):
//     cog.outl("import net.minecraft.util.profiling.ProfilerFiller;")
//]]]
//[[[end]]]
import net.minecraft.world.level.chunk.ChunkAccess;
//[[[cog
// import cog, compat
// # ChunkStatus moved into the .status subpackage at 1.20.5.
// cog.outl(compat.chunkstatus_import(mcver))
//]]]
//[[[end]]]
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * COG DRIFT (drift matrix section 2f): {@code getChunkFutureMainThread}'s result type changed at MC
 * 1.20.5 -- ancient (1.20.1/1.20.4) returns a {@code CompletableFuture<Either<ChunkAccess,
 * ChunkHolder.ChunkLoadingFailure>>}; transitional and newer return
 * {@code CompletableFuture<ChunkResult<ChunkAccess>>}. This is a structural @Invoker (bound by
 * signature), so a reflection facade cannot help -- Cog emits the correct return type + imports.
 * 26 additionally exposes {@code broadcastChangedChunks} (Cog-gated).
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheMixin {
    //[[[cog
    // import cog, compat
    // cog.outl('    @SuppressWarnings("UnnecessaryModifier")')
    // cog.outl('    @Invoker("getChunkFutureMainThread")')
    // cog.outl('    public CompletableFuture<%s> invokeGetChunkFutureMainThread(final int chunkX,' % compat.chunk_future_result_type(mcver))
    // cog.outl('                                                                                      final int chunkZ,')
    // cog.outl('                                                                                      final ChunkStatus toStatus,')
    // cog.outl('                                                                                      final boolean create);')
    //]]]
    //[[[end]]]

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
