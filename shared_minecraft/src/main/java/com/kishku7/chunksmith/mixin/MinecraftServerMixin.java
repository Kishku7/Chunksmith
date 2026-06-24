package com.kishku7.chunksmith.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
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

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements MinecraftServerExtension {
    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

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

    // Keep-awake + tick-health sampling run at HEAD of tickServer, BEFORE the dedicated
    // server's empty-pause check increments/tests its counter. Resetting emptyTicks here
    // means the counter never reaches the pause threshold while a task is active, so the
    // server never pauses (and an already-slept server is woken). Doing this at the later
    // tickConnection hook fired too late -- the counter still crossed the threshold once
    // per cycle and logged "pausing".
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void chunksmith$onTickHead(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunksmith$keepAwakeWhileGenerating();
        final boolean wgRunning = ChunksmithProvider.isLoaded() && !ChunksmithProvider.get().getGenerationTasks().isEmpty();
        WorldgenOverreachReporter.get().tick(wgRunning);
        StructureFaultReporter.get().tick(wgRunning);
    }

    @Inject(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"))
    private void chunksmith$onTickConnection(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunksmith$runChunkSystemHousekeeping(booleanSupplier);
    }

    @Unique
    private void chunksmith$keepAwakeWhileGenerating() {
        // Scoped to active generation only: once all tasks pause/finish they leave
        // generationTasks and normal pause-when-empty behaviour resumes, honouring the
        // operator's setting.
        if (ChunksmithProvider.isLoaded() && !ChunksmithProvider.get().getGenerationTasks().isEmpty()) {
            ((MinecraftServerAccess) (Object) this).setEmptyTicks(0);
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
                ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates(); // propagate removed pre-gen tickets -> holders downgrade -> chunks become unloadable
                ((ChunkMapMixin) level.getChunkSource().chunkMap).invokeTick(() -> true); // push the vanilla chunk system to unload unneeded chunks ASAP
                ((ServerChunkCacheMixin) level.getChunkSource()).invokeBroadcastChangedChunks(InactiveProfiler.INSTANCE);
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
