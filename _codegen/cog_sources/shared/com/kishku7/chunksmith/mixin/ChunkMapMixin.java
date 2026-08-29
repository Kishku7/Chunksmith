/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
    // nothing eligible to unload (tickets still held), or because eligible work is not getting done?
    // UnloadDiagnostics has the reading of ChunkMap.processUnloads that rules the second out, and
    // toDrop is the number that says which case a live server is in.
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
