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
import com.kishku7.chunksmith.util.AutoPause;
import com.kishku7.chunksmith.util.ChunkResidency;
import com.kishku7.chunksmith.util.HeapPressure;
import com.kishku7.chunksmith.util.ChunkSettleSupport;
import com.kishku7.chunksmith.util.UnloadDiagnostics;
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
import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.command.CommandArguments;
import com.kishku7.chunksmith.command.CommandLiteral;
import com.kishku7.chunksmith.util.TranslationKey;

/**
 * Keep-awake + tick-health telemetry + chunk-system housekeeping for the pre-gen path.
 *
 * <p>Cog drift: the idle-pause reset differs - MC 1.21.2..1.21.11 zero the @Shadow emptyTicks field
 * directly, 26 routes through the MinecraftServerAccess seam accessor (setEmptyTicks), and <1.21.2
 * (the field does not exist yet) emits a no-op (keep-awake N/A).
 * The housekeeping @Inject binds at tickServer TAIL on every version. (3.4.0: the 26 line used to
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

    @Shadow
    public abstract int getPlayerCount();

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
     * unloads nothing, its resident set grows, ticking it costs more, and it falls further behind -- the
     * runaway ChunkResidency was added to measure.
     *
     * <p>So the budget is "vanilla's allowance, or this much, whichever is greater". A healthy server
     * behaves exactly as it did in 3.2.0 -- haveTime is true and this is never consulted. A starved one
     * gets a small, fixed, bounded slice per tick, which is enough to drain a backlog steadily and far
     * too little to pin a core. 2 ms of a 50 ms tick is 4%.
     */
    @Unique
    private static final long CHUNKSMITH$MIN_UNLOAD_BUDGET_NANOS = 2_000_000L;

    /**
     * The floor used when nobody is playing and a run has left a backlog behind.
     *
     * <p>2 ms is tuned for "do not disturb a live server", which is the wrong constraint for an empty
     * one. An idle server has the whole tick to spare and a backlog it must clear before the next run
     * (or the next player) arrives, so it may spend a fifth of a tick on it. Still bounded, still
     * self-limiting -- the drain ends when the chunks are gone -- so this cannot become the 3.2.0
     * unbounded pin, which had no ceiling at all.
     */
    @Unique
    private static final long CHUNKSMITH$IDLE_UNLOAD_BUDGET_NANOS = 10_000_000L;

    /**
     * Chunk-ticket work waiting for the safe point. See MinecraftServerExtension#chunksmith$atTicketSafePoint
     * for the whole argument; the short version is that a ticket mutation run from the server executor
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

    /**
     * Player count as of the previous tick, so the moment the server becomes empty can be noticed.
     *
     * <p>That transition is when a stalled drain deserves another go: the unload floor jumps to the
     * idle budget, and whatever a player was doing to keep chunks resident has stopped. Without this a
     * drain that gave up while somebody was online stays given-up for ever, and the server sits degraded
     * until it is restarted -- see ChunkResidency#noteDrainBudget for the measurement.
     */
    @Unique
    private int chunksmith$lastPlayerCount = -1;

    @Unique
    private long chunksmith$lastTickNanos = 0L;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void chunksmith$onTickHead(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        // The ticket safe point, deliberately here and not in the housekeeping hook below: on a dedicated
        // server tickServer HEAD fires unconditionally, every tick, on every game version -- including the
        // empty-server tick, which returns before TAIL is reached.
        //
        // It is not sufficient on its own (mod_support #17). This comment used to claim HEAD covered
        // "the pause tick" too, conflating a dedicated server's empty tick with an integrated
        // server's paused tick. They are not the same: IntegratedServer.tickServer sets
        // `paused = Minecraft.isPaused() || players.isEmpty()`, and when paused calls tickPaused()
        // and returns -- super.tickServer is never reached, so neither is this injection. From 3.3.0
        // until that was found, a paused single-player pre-gen queued ticket work that nothing ever
        // drained and sat at zero chunks, silently. IntegratedServerMixin now drains on the paused
        // path; see MinecraftServerExtension#chunksmith$drainTicketSafePointNow.
        // Draining in the housekeeping hook stalled a 26.1.2 pre-gen at zero chunks back when that
        // hook bound to INVOKE tickConnection()V; 3.4.0 moved it to TAIL (see
        // compat.housekeeping_inject_at for why that binding never fired), but the drain stays here.
        //
        // Ordering: what this drain queues is applied by vanilla's own flush, not ours.
        // ServerChunkCache.tick() calls runDistanceManagerUpdates() immediately before tickChunks(),
        // and tickChunks is the walk of the simulation chunk tracker that must not be disturbed. So
        // every ticket mutation made here is fully propagated before that walk begins, and nothing of
        // ours is left pending for a re-entrant pump to apply underneath it.
        this.chunksmith$drainTicketSafePoint();
        this.chunksmith$keepAwakeWhileGenerating();
        final boolean wgRunning = ChunksmithProvider.isLoaded() && !ChunksmithProvider.get().getGenerationTasks().isEmpty();
        // Residency is published every tick, running or not. 3.5.0 cleared it the moment a task ended,
        // which is precisely when the backlog that task left behind most needed watching.
        this.chunksmith$reportChunkResidency();
        final int players = this.getPlayerCount();
        // Tell the drain whether it is being given a real budget, so a no-progress verdict can only be
        // reached when it actually had a chance to make progress.
        ChunkResidency.noteDrainBudget(players == 0);
        if (players == 0 && this.chunksmith$lastPlayerCount > 0) {
            ChunkResidency.reconsiderDrain();
        }
        this.chunksmith$lastPlayerCount = players;
        // Keep the unload pass armed while a finished run still owes the server a drain. Without this
        // the backlog is orphaned: nothing arms housekeeping on an idle server, and vanilla's own pass
        // does nothing once the tick is over budget -- which it is, because of the retained chunks.
        if (ChunkResidency.isDraining() || ChunkResidency.isGenerationHeld()) {
            this.chunksmith$markChunkSystemHousekeeping();
        }
        // Pump the settle windows on the tick, not on chunk arrivals. Held tickets used to come back
        // only when a new chunk was offered, so holding dispatch stopped the frontier shrinking and the
        // residency gate suppressed its own recovery.
        ChunkSettleSupport.tick(this.chunksmith$gameTimeForSettle());
        this.chunksmith$tickAutoResume(wgRunning);
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
        }
        // Tick health is measured every tick, generating or not. It used to be sampled only while a
        // run was active and reset to a nominal 50 ms otherwise, so a run had no idea what the server
        // cost before it started and the throttle could only steer on absolute tick time. That is fatal
        // on a server whose idle baseline already sits at the configured target -- the run that measured
        // 85.2 ms while generating never showed the governor a healthy tick; see TickBudget for the
        // paused reading beside it. Hence: sample unconditionally.
        final long now = System.nanoTime();
        final long prev = this.chunksmith$lastTickNanos;
        this.chunksmith$lastTickNanos = now;
        if (prev != 0L) {
            final double dtMs = (now - prev) / 1.0e6D;
            // Ignore absurd gaps (first tick after a pause, GC stalls) so one outlier cannot poison
            // the average.
            if (dtMs > 0.0D && dtMs < 10_000.0D) {
                this.chunksmith$mspt = (this.chunksmith$mspt * 0.8D) + (dtMs * 0.2D);
            }
        }
    }

    /**
     * A monotonic tick clock for the settle window, taken from the overworld's game time.
     *
     * <p>The window is given the same clock {@code offer()} uses, so a delay measured in ticks means
     * the same thing on both paths. Falls back to zero on a server with no levels, which cannot happen
     * in practice but must not throw if it ever does.
     */
    @Unique
    private long chunksmith$gameTimeForSettle() {
        for (ServerLevel level : this.getAllLevels()) {
            return level.getGameTime();
        }
        return 0L;
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
        long toDrop = 0L;
        long unloadQueue = 0L;
        long pendingUnloads = 0L;
        boolean hasTickets = false;
        for (ServerLevel level : this.getAllLevels()) {
            loaded += level.getChunkSource().getLoadedChunksCount();
            // The eligibility question, answered directly instead of inferred from tick times. See
            // UnloadDiagnostics: toDrop == 0 while chunks are resident means nothing is eligible to
            // unload, which is a ticket problem, not a throughput one -- and the two were
            // indistinguishable from outside for three releases.
            final ChunkMapMixin chunkMap = (ChunkMapMixin) level.getChunkSource().chunkMap;
            toDrop += chunkMap.getToDrop().size();
            unloadQueue += chunkMap.getUnloadQueue().size();
            pendingUnloads += chunkMap.getPendingUnloads().size();
            hasTickets |= level.getChunkSource().chunkMap.getDistanceManager().hasTickets();
        }
        ChunkResidency.report(loaded);
        UnloadDiagnostics.report(loaded, toDrop, unloadQueue, pendingUnloads, hasTickets);
    }

    /**
     * Watch for the server recovering, and restart a run we paused.
     *
     * <p>Deliberately outside {@code GenerationTask}: the task is gone by the time this matters, so
     * the thing that restarts it cannot live inside it. Only a run auto-paused by Chunksmith is ever
     * resumed -- a human {@code /cs pause} is a decision, not a fault, and must stay paused.
     *
     * <p>"Healthy" is the tick keeping up and the heap having real headroom. Both, because either one
     * alone comes back before the other and a resume on half the evidence just walks into the same
     * wall. The grace period then requires it to hold.
     */
    @Unique
    private void chunksmith$tickAutoResume(boolean generationRunning) {
        if (!AutoPause.isAutoPaused() || generationRunning) {
            return;
        }
        final long now = System.currentTimeMillis();
        final double heap = HeapPressure.usedPercent();
        final boolean healthy = this.chunksmith$mspt <= 55.0D && heap >= 0.0D && heap < 70.0D;
        AutoPause.noteHealthy(healthy, now);
        if (!AutoPause.shouldResume(now)) {
            return;
        }
        final String world = AutoPause.pausedWorld();
        AutoPause.clearAutoPaused();
        if (!ChunksmithProvider.isLoaded()) {
            return;
        }
        final Chunksmith chunky = ChunksmithProvider.get();
        chunky.getServer().getConsole().sendMessagePrefixed(
                TranslationKey.TASK_AUTO_RESUMED,
                AutoPause.graceMillis() / 1000L, world);
        chunky.getCommands().get(CommandLiteral.CONTINUE)
                .execute(chunky.getServer().getConsole(),
                        CommandArguments.empty());
    }

    @Override
    public double chunksmith$getMillisPerTick() {
        return this.chunksmith$mspt;
    }

    @Override
    public void chunksmith$atTicketSafePoint(Runnable task) {
        this.chunksmith$ticketSafePointQueue.add(task);
        // A released pre-gen ticket only becomes an unloadable chunk once the holders are
        // downgraded, which is what housekeeping does -- so arm it rather than leave the job half done.
        this.chunksmith$markChunkSystemHousekeeping();
    }

    @Override
    public void chunksmith$drainTicketSafePointNow() {
        this.chunksmith$drainTicketSafePoint();
    }

    @Override
    public boolean chunksmith$onTicketSafePoint() {
        return Thread.currentThread() == this.chunksmith$ticketSafePointThread;
    }

    @Override
    public void chunksmith$runChunkSystemHousekeeping(BooleanSupplier haveTime) {
        if (this.chunksmith$needChunkSystemHousekeeping.compareAndSet(true, false)) {
            // One deadline for the whole pass, not one per level: the floor is what Chunksmith is
            // willing to spend on unloading this tick in total, and a per-level deadline would multiply
            // it by the number of dimensions.
            // The bigger budget applies whenever nobody is playing and nothing is being generated
            // because one of our own gates said so -- a drain after a run, or a gate holding dispatch
            // mid-run. In both cases the tick is free and unloading is the only thing that can end the
            // situation. 3.5.3 gated this on the drain alone, so a mid-run hold got 2 ms and the
            // resident count did not fall at all across a 120-second hold.
            final long floor = this.getPlayerCount() == 0
                    && (ChunkResidency.isDraining() || ChunkResidency.isGenerationHeld())
                    ? CHUNKSMITH$IDLE_UNLOAD_BUDGET_NANOS
                    : CHUNKSMITH$MIN_UNLOAD_BUDGET_NANOS;
            final long deadline = System.nanoTime() + floor;
            final BooleanSupplier budget = () -> haveTime.getAsBoolean() || System.nanoTime() < deadline;
            for (ServerLevel level : this.getAllLevels()) {
                // Deliberately not guarded on C2ME (mod_support #16). Server_Tests/cs-c2me-cancel-gate
                // reproduced the crash with the guard in place, arriving instead through vanilla
                // ServerChunkCache.pollTask -- so the ticket map was already corrupt. The cause was ticket
                // mutation reaching the chunk system from the server executor mid-iteration, now confined
                // to the ticket safe point at tickServer HEAD (see chunksmith$onTickHead).
                ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates(); // propagate removed pre-gen tickets -> holders downgrade -> chunks become unloadable
                // mod_support #11: this was invokeTick(() -> true), i.e. "unlimited time", ignoring the
                // haveTime the method already receives. ~13k queued unloads after a big pre-gen radius
                // then pinned the server thread near 100% CPU inside ChunkMap.scheduleUnload for 60+
                // minutes, starving command processing. haveTime is vanilla's own
                // tickServer(BooleanSupplier hasTimeLeft) budget, so passing it through drains a large
                // backlog across many ticks instead of forcing it through one synchronous call.
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
     * Run the queued chunk-ticket work. The one place Chunksmith mutates a chunk ticket.
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
