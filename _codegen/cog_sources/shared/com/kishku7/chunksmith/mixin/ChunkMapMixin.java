package com.kishku7.chunksmith.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

@SuppressWarnings("UnnecessaryInterfaceModifier")
@Mixin(ChunkMap.class)
public interface ChunkMapMixin {
    @Invoker("getVisibleChunkIfPresent")
    public ChunkHolder invokeGetVisibleChunkIfPresent(long pos);

    @Invoker("readChunk")
    public CompletableFuture<Optional<CompoundTag>> invokeReadChunk(ChunkPos pos);

    @Invoker
    void invokeTick(BooleanSupplier booleanSupplier);

    @Accessor
    BlockableEventLoop<Runnable> getMainThreadExecutor();

    // --- unload diagnostics (3.5.5) ---------------------------------------------------------------
    // The question these answer: when a drain frees nothing, is it because the chunk system has
    // nothing ELIGIBLE to unload (tickets still held), or because eligible work is not getting done?
    // Reading ChunkMap.processUnloads proves the second cannot be starved by our budget -- its toDrop
    // loop consults no budget, and its unloadQueue drain runs down to 2000 entries regardless. So the
    // answer is almost certainly the first, and toDrop is the number that says so.
    //
    // Field names and types are identical on every supported version (1.20.1 through 26.3), so these
    // need no Cog handling. toDrop is package-private; the other two are private.

    @Accessor("toDrop")
    LongSet getToDrop();

    @Accessor("unloadQueue")
    Queue<Runnable> getUnloadQueue();

    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> getPendingUnloads();

    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> getVisibleChunkMap();
}
