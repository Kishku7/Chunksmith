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
import com.kishku7.chunksmith.util.ChunkResidency;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import com.kishku7.chunksmith.util.WorldgenOverreachReporter;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * KEEP-AWAKE + tick-health telemetry + chunk-system housekeeping for the pre-gen path.
 *
 * <p>COG DRIFT: the idle-pause reset differs - MC 1.21.2..1.21.11 zero the @Shadow emptyTicks field
 * directly, 26 routes through the MinecraftServerAccess seam accessor (setEmptyTicks), and <1.21.2
 * (the field does not exist yet) emits a no-op (keep-awake N/A).
 * The housekeeping @Inject binds at tickServer TAIL on EVERY version. (3.4.0: the 26 line used to
 * bind at INVOKE tickConnection()V, a call site tickServer only reaches inside the empty-server
 * pause branch that returns early -- so housekeeping never actually ran there. See
 * compat.housekeeping_inject_at for the bytecode.) 26 also runs an extra
 * ServerChunkCache.broadcastChangedChunks(ProfilerFiller) invoker that older lines lack.
 * All three are Cog-emitted from compat.py.
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

    /**
     * The unload work we guarantee ourselves each tick, even on a server that is already over budget.
     *
     * <p>3.2.0 fixed a 60-minute CPU pin by passing vanilla's own {@code haveTime} into the unload pass
     * instead of a hardcoded true. That was right, and it introduced the opposite failure: {@code
     * haveTime} is false for the whole tick once the server is behind, so a server that has fallen behind
     * unloads NOTHING, its resident set grows, ticking it costs more, and it falls further behind. A live
     * server reached 75,045 resident chunks that way (2026-08-19).
     *
     * <p>So the budget is "vanilla's allowance, OR this much, whichever is greater". A healthy server
     * behaves exactly as it did in 3.2.0 -- haveTime is true and this is never consulted. A starved one
     * gets a small, fixed, bounded slice per tick, which is enough to drain a backlog steadily and far
     * too little to pin a core. 2 ms of a 50 ms tick is 4%.
     */
    @Unique
    private static final long CHUNKSMITH$MIN_UNLOAD_BUDGET_NANOS = 2_000_000L;

    /**
     * Chunk-ticket work waiting for the safe point. See MinecraftServerExtension#chunksmith$atTicketSafePoint
     * for the whole argument; the short version is that a ticket mutation run from the server EXECUTOR
     * lands inside ServerChunkCache.tickChunks' iteration of the simulation chunk tracker, and the
     * distance-manager flush that the next executor pump performs then writes the map being iterated.
     */
    @Unique
    private final Queue<Runnable> chunksmith$ticketSafePointQueue = new ConcurrentLinkedQueue<>();

    /**
     * The thread currently draining the safe-point queue, or null. Volatile and identity-compared
     * rather than a boolean flag: it answers "am I the drain?" correctly for every thread, with no
     * chance of a stale true letting an off-thread caller mutate tickets inline.
     */
    @Unique
    private volatile Thread chunksmith$ticketSafePointThread;

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
        // THE TICKET SAFE POINT. Deliberately here and not in the housekeeping hook below: tickServer
        // HEAD is the one injection point that fires unconditionally, every tick, on EVERY game
        // version -- including the empty-server pause tick, which returns before TAIL is reached.
        // Draining in the housekeeping hook stalled a 26.1.2 pre-gen at zero chunks back when that
        // hook bound to INVOKE tickConnection()V; 3.4.0 moved it to TAIL (see
        // compat.housekeeping_inject_at for why that binding never fired), but the drain stays here.
        //
        // ORDERING: what this drain queues is applied by vanilla's own flush, not ours.
        // ServerChunkCache.tick() calls runDistanceManagerUpdates() immediately BEFORE tickChunks(),
        // and tickChunks is the walk of the simulation chunk tracker that must not be disturbed. So
        // every ticket mutation made here is fully propagated before that walk begins, and nothing of
        // ours is left pending for a re-entrant pump to apply underneath it.
        this.chunksmith$drainTicketSafePoint();
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
            // Housekeeping is normally armed by a ticket mutation. That is not enough during a pregen:
            // when the throttle has cut dispatch to its floor, ticket mutations become rare exactly when
            // the unload backlog most needs draining -- the throttle would be throttling the cure. While
            // a run is active, arm it every tick.
            this.chunksmith$markChunkSystemHousekeeping();
            this.chunksmith$reportChunkResidency();
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
            // baseline rather than a stale idle-sleep interval, and drop the residency reading so it
            // can never gate a later run.
            ChunkResidency.clear();
            this.chunksmith$lastTickNanos = 0L;
            this.chunksmith$mspt = 50.0D;
        }
    }

    /**
     * Publish how many chunks the server is holding, for the generation throttle to gate on.
     *
     * <p>{@code getLoadedChunksCount()} is the same number the crash report prints as {@code Chunks[S]
     * W:} -- public and unchanged on every MC version from 1.20.1 through 26.x, so this needs no Cog
     * drift handling. Summed across dimensions because memory is, and a pregen in one dimension is
     * perfectly capable of being starved by chunks resident in another.
     */
    @Unique
    private void chunksmith$reportChunkResidency() {
        long loaded = 0L;
        for (ServerLevel level : this.getAllLevels()) {
            loaded += level.getChunkSource().getLoadedChunksCount();
        }
        ChunkResidency.report(loaded);
    }

    @Override
    public double chunksmith$getMillisPerTick() {
        return this.chunksmith$mspt;
    }

    @Override
    public void chunksmith$atTicketSafePoint(final Runnable task) {
        this.chunksmith$ticketSafePointQueue.add(task);
        // A released pre-gen ticket only becomes an unloadable chunk once the holders are
        // downgraded, which is what housekeeping does -- so arm it rather than leave the job half done.
        this.chunksmith$markChunkSystemHousekeeping();
    }

    @Override
    public boolean chunksmith$onTicketSafePoint() {
        return Thread.currentThread() == this.chunksmith$ticketSafePointThread;
    }

    @Override
    public void chunksmith$runChunkSystemHousekeeping(BooleanSupplier haveTime) {
        if (this.chunksmith$needChunkSystemHousekeeping.compareAndSet(true, false)) {
            // ONE deadline for the whole pass, not one per level: the floor is what Chunksmith is
            // willing to spend on unloading this tick in total, and a per-level deadline would multiply
            // it by the number of dimensions.
            final long deadline = System.nanoTime() + CHUNKSMITH$MIN_UNLOAD_BUDGET_NANOS;
            final BooleanSupplier budget = () -> haveTime.getAsBoolean() || System.nanoTime() < deadline;
            for (ServerLevel level : this.getAllLevels()) {
                // NOT guarded on C2ME, deliberately (mod_support #16, 2026-08-12). A guard was tried
                // here on the theory that this call was the ticket-map race; the C2ME cancel gate
                // (Server_Tests/cs-c2me-cancel-gate) proved it was NOT -- the crash reproduced with the
                // guard in place, arriving instead through vanilla ServerChunkCache.pollTask, i.e. the
                // map was already corrupt. The real cause was ticket mutation reaching the chunk
                // system from the server EXECUTOR, mid-iteration; that is now confined to the ticket
                // safe point at tickServer HEAD (see chunksmith$onTickHead).
                ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates(); // propagate removed pre-gen tickets -> holders downgrade -> chunks become unloadable
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
                ((ChunkMapMixin) level.getChunkSource().chunkMap).invokeTick(budget); // bounded: vanilla's budget, floored so a starved tick still unloads
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

    /**
     * Run the queued chunk-ticket work. The ONE place Chunksmith mutates a chunk ticket.
     *
     * <p>Bounded by the queue size on entry. A task may enqueue another -- a chunk future completing
     * during the drain hands its ticket release straight back -- and running those in the same pass
     * would let a busy pre-gen extend the drain indefinitely inside a single tick. Whatever arrives
     * mid-drain is picked up by the next tick's drain.
     */
    @Unique
    private void chunksmith$drainTicketSafePoint() {
        int budget = this.chunksmith$ticketSafePointQueue.size();
        if (budget <= 0) {
            return;
        }
        this.chunksmith$ticketSafePointThread = Thread.currentThread();
        try {
            Runnable task;
            while (budget-- > 0 && (task = this.chunksmith$ticketSafePointQueue.poll()) != null) {
                task.run();
            }
        } finally {
            this.chunksmith$ticketSafePointThread = null;
        }
    }

    @Override
    public void chunksmith$markChunkSystemHousekeeping() {
        this.chunksmith$needChunkSystemHousekeeping.set(true);
    }
}
