/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
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

//[[[cog
// import cog, compat
// for imp in compat.ioworker_executor_imports(mcver):
//     cog.outl(imp)
//]]]
//[[[end]]]
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//[[[cog
// import cog, compat
// cog.outl(compat.pending_writes_import(mcver))
//]]]
//[[[end]]]

/**
 * Exposes IOWorker internals used by the worldgen entity-unload fix.
 *
 * <p>COG DRIFT (AXIS A, drift matrix 2b): the single-thread executor owning all of the worker's
 * mutable state (pendingWrites + the RegionFileStorage region cache) changed at MC 1.21.4 --
 * {@code mailbox}/{@code ProcessorMailbox<StrictQueue.IntRunnable>}/{@code Map} on 1.20.1 ..
 * 1.21.3, {@code consecutiveExecutor}/{@code PriorityConsecutiveExecutor}/{@code SequencedMap} on
 * 1.21.4 .. 26. The entity probe is submitted onto this executor so it sees a consistent snapshot
 * instead of racing the writer thread.
 *
 * <p>{@code pendingWrites} is a plain non-thread-safe map mutated solely on the executor thread:
 * only ever touch it from inside an executor task. {@code containsKey} there catches entities
 * persisted-but-not-yet-on-disk, which a raw region-file read would miss. {@code storage} is
 * byte-stable across every version; used only to resolve the entity-region folder Path.
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    //[[[cog
    // import cog, compat
    // cog.outl('    @Accessor("%s")' % compat.ioworker_executor_field(mcver))
    // cog.outl('    %s %s();' % (compat.ioworker_executor_type(mcver), compat.ioworker_executor_getter(mcver)))
    //]]]
    //[[[end]]]

    //[[[cog
    // import cog, compat
    // cog.outl('    @Accessor("pendingWrites")')
    // cog.outl('    %s<?, ?> chunksmith$getPendingWrites();' % compat.pending_writes_type(mcver))
    //]]]
    //[[[end]]]

    @Accessor("storage")
    RegionFileStorage chunksmith$getStorage();
}
