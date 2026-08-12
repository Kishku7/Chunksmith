package com.kishku7.chunksmith.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
//[[[cog
// import cog, compat
// # InactiveProfiler is only referenced by the 26-only broadcastChangedChunks call.
// if compat.needs_inactive_profiler_import(mcver):
//     cog.outl("import net.minecraft.util.profiling.InactiveProfiler;")
//]]]
//[[[end]]]
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ChunksmithProvider;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import com.kishku7.chunksmith.util.WorldgenOverreachReporter;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * KEEP-AWAKE + tick-health telemetry + chunk-system housekeeping for the pre-gen path.
 *
 * <p>COG DRIFT: the idle-pause reset differs - MC 1.21.2..1.21.11 zero the @Shadow emptyTicks field
 * directly, 26 routes through the MinecraftServerAccess seam accessor (setEmptyTicks), and <1.21.2
 * (the field does not exist yet) emits a no-op (keep-awake N/A).
 * The housekeeping @Inject binds at tickServer TAIL pre-26 vs the 26-only tickConnection()V INVOKE
 * hook. 26 also runs an extra ServerChunkCache.broadcastChangedChunks(ProfilerFiller) invoker that
 * older lines lack. All three are Cog-emitted from compat.py.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements MinecraftServerExtension {
    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    //[[[cog
    // import cog, compat
    // # The emptyTicks idle-pause counter exists from MC 1.21.2 onward; pre-26 with the field
    // # present needs the @Shadow for the direct reset (26 uses the accessor; <1.21.2 has no field).
    // if compat.needs_empty_ticks_shadow(mcver):
    //     cog.outl("    @Shadow")
    //     cog.outl("    private int emptyTicks;")
    //]]]
    //[[[end]]]

    @Unique
    private final AtomicBoolean chunksmith$needChunkSystemHousekeeping = new AtomicBoolean(false);

    // Tick-health telemetry, sampled only while a generation task runs. Measured as the
    // wall-clock interval between server ticks (EWMA-smoothed). A healthy 20 TPS server
    // sleeps to ~50 ms/tick; when it can no longer keep up the interval climbs past 50 ms.
    // Computed directly rather than read from a Mojang-mapped getter so it stays correct
    // across game versions without a mapping dependency.
    @Unique
    private volatile double chunksmith$mspt = 50.0D;
    @Unique
    private long chunksmith$lastTickNanos = 0L;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void chunksmith$onTickHead(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunksmith$keepAwakeWhileGenerating();
        final boolean wgRunning = ChunksmithProvider.isLoaded() && !ChunksmithProvider.get().getGenerationTasks().isEmpty();
        WorldgenOverreachReporter.get().tick(wgRunning);
        StructureFaultReporter.get().tick(wgRunning);
    }

    //[[[cog
    // import cog, compat
    // cog.outl('    @Inject(method = "tickServer", %s)' % compat.housekeeping_inject_at(mcver))
    //]]]
    //[[[end]]]
    private void chunksmith$onHousekeepingHook(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunksmith$runChunkSystemHousekeeping(booleanSupplier);
    }

    @Unique
    private void chunksmith$keepAwakeWhileGenerating() {
        // Scoped to active generation only: once all tasks pause/finish they leave
        // generationTasks and normal pause-when-empty behaviour resumes, honouring the
        // operator's setting.
        if (ChunksmithProvider.isLoaded() && !ChunksmithProvider.get().getGenerationTasks().isEmpty()) {
            //[[[cog
            // import cog, compat
            // cog.outl("            %s" % compat.empty_ticks_reset(mcver))
            //]]]
            //[[[end]]]
            final long now = System.nanoTime();
            final long prev = this.chunksmith$lastTickNanos;
            this.chunksmith$lastTickNanos = now;
            if (prev != 0L) {
                final double dtMs = (now - prev) / 1.0e6D;
                // Ignore absurd gaps (first tick after a pause, GC stalls) so one outlier
                // can't poison the average.
                if (dtMs > 0.0D && dtMs < 10_000.0D) {
                    this.chunksmith$mspt = (this.chunksmith$mspt * 0.8D) + (dtMs * 0.2D);
                }
            }
        } else {
            // No active generation -- reset so the next run starts from a clean, healthy
            // baseline rather than a stale idle-sleep interval.
            this.chunksmith$lastTickNanos = 0L;
            this.chunksmith$mspt = 50.0D;
        }
    }

    @Override
    public double chunksmith$getMillisPerTick() {
        return this.chunksmith$mspt;
    }

    @Override
    public void chunksmith$runChunkSystemHousekeeping(BooleanSupplier haveTime) {
        if (this.chunksmith$needChunkSystemHousekeeping.compareAndSet(true, false)) {
            for (ServerLevel level : this.getAllLevels()) {
                // C2ME ticket-race guard on the REMOVE side (mod_support #16, 2026-08-12).
                // The identical guard was put on the ADD side (FabricWorld, after addTicketWithRadius)
                // and ported fleet-wide on 2026-08-03 -- but this call site was missed, and it is the
                // one that does the MOST work: it exists to propagate REMOVED pre-gen tickets, so a
                // cancel dumps thousands of removals through it at once. Under C2ME that re-enters the
                // rewritten concurrent chunk system and corrupts its fastutil ticket map -- reported as
                // ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 513 in
                // Long2ByteOpenHashMap.rehash, under c2me-base$consolidatePriorityUpdates, on cancelling
                // a pregen at ~65% (MC 26.2). C2ME consolidates these updates itself, which is exactly
                // why the add side is safe to skip; the same reasoning applies here.
                // LESSON: a compat guard applied to ONE call site of a pattern must be applied to EVERY
                // call site of it -- grep for the pattern, do not fix the one in front of you.
                if (!PlatformCompat.ENABLE_C2ME_TICKET_COMPAT) {
                    ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates(); // propagate removed pre-gen tickets -> holders downgrade -> chunks become unloadable
                }
                // FIX (2026-08-02, mod_support #11): was invokeTick(() -> true) -- "ASAP", ignoring the
                // haveTime this method already receives. That told vanilla's unload pass it always has
                // unlimited time, so ChunkMap.tick()/processUnloads() ran fully unbounded on the main
                // thread every tick housekeeping fired. Harmless on a small backlog; on a large one
                // (reported: ~13k+ queued unloads after a big pre-gen radius) it pinned the server
                // thread near 100% CPU inside ChunkMap.scheduleUnload for 60+ minutes, starving command
                // processing. haveTime is vanilla's own tickServer(BooleanSupplier hasTimeLeft) budget --
                // the same signal vanilla's own unload processing respects everywhere else. Passing it
                // through here (instead of a hardcoded true) makes the unload pass self-limit per tick
                // and drain a large backlog incrementally across many ticks instead of forcing it all
                // through in one synchronous call.
                ((ChunkMapMixin) level.getChunkSource().chunkMap).invokeTick(haveTime); // bounded: respect the real per-tick time budget
                //[[[cog
                // import cog, compat
                // cog.outl("                %s" % compat.broadcast_changed_chunks_call(mcver))
                //]]]
                //[[[end]]]
                if (!PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS) {
                    // note: Moonrise destroys the vanilla entity system, so skip it here if it's present
                    ((ServerLevelMixin) level).getEntityManager().tick();
                }
            }
        }
    }

    @Override
    public void chunksmith$markChunkSystemHousekeeping() {
        this.chunksmith$needChunkSystemHousekeeping.set(true);
    }
}
