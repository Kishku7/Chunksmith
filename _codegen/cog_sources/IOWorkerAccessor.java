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
 * <p>COG DRIFT (AXIS A -- IOWorker executor primitive, drift matrix section 2b): the single-thread
 * executor that owns ALL of the worker's mutable state (pendingWrites + the RegionFileStorage region
 * cache) changed shape at MC 1.21.4.
 * <ul>
 *   <li>LEGACY (1.20.1 .. 1.21.3): field {@code mailbox}, a
 *       {@code ProcessorMailbox<StrictQueue.IntRunnable>}, and {@code pendingWrites} declared as a
 *       plain {@code Map}.</li>
 *   <li>MODERN (1.21.4 .. 26): field {@code consecutiveExecutor}, a
 *       {@code PriorityConsecutiveExecutor}, and {@code pendingWrites} a {@code SequencedMap}.</li>
 * </ul>
 * The entity-unload fix submits its "does this chunk have persisted entity data?" probe onto this
 * executor so the check observes a consistent snapshot -- serialized against in-flight stores --
 * instead of racing the writer thread.
 *
 * <p>{@code pendingWrites} holds chunk writes queued-but-not-yet-flushed. It is a plain
 * (non-thread-safe) map mutated solely on the executor thread; only ever touch it from inside an
 * executor task. The fix calls {@code containsKey} there to catch entities that are persisted-but-not-
 * yet-on-disk (the case a raw region-file read would miss). Its {@code size()} is also read elsewhere
 * for write backpressure.
 *
 * <p>{@code storage} is the RegionFileStorage the worker writes through (byte-stable across every
 * version); the fix uses it only to resolve the entity-region folder Path (read-only metadata).
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
