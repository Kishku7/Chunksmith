package org.popcraft.chunky.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.popcraft.chunky.ChunkyNeoForge;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.util.StructureFaultReporter;
import org.popcraft.chunky.util.WorldgenOverreachReporter;
import org.popcraft.chunky.ducks.MinecraftServerExtension;
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

    // 1.21.2 ADAPTATION: the empty-server pause feature lands at 1.21.2 (the pauseWhenEmptySeconds
    // server property, snapshot 24w33a). When no players are online for longer than that, the
    // server stops advancing the world (tickConnection skips tickServer once emptyTicks crosses the
    // threshold), which would freeze chunk pre-generation. KEEP-AWAKE (F2): while a Chunky task is
    // running we reset emptyTicks to 0 every tick so the idle-pause never engages. This field does
    // NOT exist on 1.21.1 (hence keep-awake was N/A there); it is present from 1.21.2 onward.
    @Shadow
    private int emptyTicks;

    @Unique
    private final AtomicBoolean chunky$needChunkSystemHousekeeping = new AtomicBoolean(false);

    // Tick-health telemetry, sampled only while a generation task runs. Measured as the
    // wall-clock interval between server ticks (EWMA-smoothed). A healthy 20 TPS server
    // sleeps to ~50 ms/tick; when it can no longer keep up the interval climbs past 50 ms.
    // Computed directly rather than read from a Mojang-mapped getter so it stays correct
    // across game versions without a mapping dependency.
    @Unique
    private volatile double chunky$mspt = 50.0D;
    @Unique
    private long chunky$lastTickNanos = 0L;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void chunky$onTickHead(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunky$keepAwakeWhileGenerating();
        final boolean wgRunning = ChunkyProvider.isLoaded() && !ChunkyProvider.get().getGenerationTasks().isEmpty();
        WorldgenOverreachReporter.get().tick(wgRunning);
        StructureFaultReporter.get().tick(wgRunning);
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void chunky$onTickTail(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunky$runChunkSystemHousekeeping(booleanSupplier);
    }

    @Unique
    private void chunky$keepAwakeWhileGenerating() {
        if (ChunkyProvider.isLoaded() && !ChunkyProvider.get().getGenerationTasks().isEmpty()) {
            // KEEP-AWAKE (1.21.2+): zero out the idle counter so the empty-server pause never
            // engages while we are generating, even with no players online.
            this.emptyTicks = 0;
            final long now = System.nanoTime();
            final long prev = this.chunky$lastTickNanos;
            this.chunky$lastTickNanos = now;
            if (prev != 0L) {
                final double dtMs = (now - prev) / 1.0e6D;
                // Ignore absurd gaps (first tick after a pause, GC stalls) so one outlier
                // can't poison the average.
                if (dtMs > 0.0D && dtMs < 10_000.0D) {
                    this.chunky$mspt = (this.chunky$mspt * 0.8D) + (dtMs * 0.2D);
                }
            }
        } else {
            // No active generation — reset so the next run starts from a clean, healthy
            // baseline rather than a stale idle-sleep interval.
            this.chunky$lastTickNanos = 0L;
            this.chunky$mspt = 50.0D;
        }
    }

    @Override
    public double chunky$getMillisPerTick() {
        return this.chunky$mspt;
    }

    @Override
    public void chunky$runChunkSystemHousekeeping(BooleanSupplier haveTime) {
        if (this.chunky$needChunkSystemHousekeeping.compareAndSet(true, false)) {
            for (ServerLevel level : this.getAllLevels()) {
                ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates();
                ((ChunkMapMixin) level.getChunkSource().chunkMap).invokeTick(() -> true); // push the vanilla chunk system to unload unneeded chunks ASAP
                if (!ChunkyNeoForge.ENABLE_MOONRISE_WORKAROUNDS) {
                    // note: Moonrise destroys the vanilla entity system, so skip it here if it's present
                    ((ServerLevelMixin) level).getEntityManager().tick();
                }
            }
        }
    }

    @Override
    public void chunky$markChunkSystemHousekeeping() {
        this.chunky$needChunkSystemHousekeeping.set(true);
    }
}
