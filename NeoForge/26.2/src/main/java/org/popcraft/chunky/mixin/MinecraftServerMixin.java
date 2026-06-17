package org.popcraft.chunky.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
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

    @Unique
    private final AtomicBoolean chunky$needChunkSystemHousekeeping = new AtomicBoolean(false);

    // Tick-health telemetry, sampled only while a generation task runs (EWMA of the
    // wall-clock interval between server ticks). ~50 ms = healthy 20 TPS. Mirrors Fabric.
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

    @Inject(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"))
    private void chunky$onTickConnection(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunky$runChunkSystemHousekeeping(booleanSupplier);
    }

    @Unique
    private void chunky$keepAwakeWhileGenerating() {
        if (ChunkyProvider.isLoaded() && !ChunkyProvider.get().getGenerationTasks().isEmpty()) {
            ((MinecraftServerAccess) (Object) this).setEmptyTicks(0);
            final long now = System.nanoTime();
            final long prev = this.chunky$lastTickNanos;
            this.chunky$lastTickNanos = now;
            if (prev != 0L) {
                final double dtMs = (now - prev) / 1.0e6D;
                if (dtMs > 0.0D && dtMs < 10_000.0D) {
                    this.chunky$mspt = (this.chunky$mspt * 0.8D) + (dtMs * 0.2D);
                }
            }
        } else {
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
                ((ChunkMapMixin) level.getChunkSource().chunkMap).invokeTick(() -> true);
                ((ServerChunkCacheMixin) level.getChunkSource()).invokeBroadcastChangedChunks(InactiveProfiler.INSTANCE);
                if (!ChunkyNeoForge.ENABLE_MOONRISE_WORKAROUNDS) {
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
