package org.popcraft.chunky.mixin;

import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes IOWorker internals used by the worldgen entity-unload fix (LEGACY: MC 1.20.1 - 1.21.3).
 *
 * <p>The pre-1.21.4 worker drains its single thread via a {@code ProcessorMailbox} rather than a
 * {@code PriorityConsecutiveExecutor}. The fix enqueues its "does this chunk have persisted entity
 * data?" probe onto this mailbox so the check is serialized against in-flight stores instead of
 * racing the writer thread.
 *
 * <p>{@code pendingWrites} is a plain {@link Map} (LinkedHashMap) on this line, mutated only on the
 * mailbox thread; the fix reads it via {@code containsKey} inside a mailbox task to catch entities
 * persisted-but-not-yet-on-disk.
 *
 * <p>{@code storage} is the RegionFileStorage the worker writes through; the fix uses it only to
 * resolve the entity-region folder Path.
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("mailbox")
    ProcessorMailbox<StrictQueue.IntRunnable> chunky$getMailbox();

    @Accessor("pendingWrites")
    Map<?, ?> chunky$getPendingWrites();

    @Accessor("storage")
    RegionFileStorage chunky$getStorage();
}
