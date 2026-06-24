package com.kishku7.chunksmith.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import com.kishku7.chunksmith.ChunksmithNeoForge;
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

    // Tick-health telemetry, sampled only while a generation task runs (EWMA of the
    // wall-clock interval between server ticks). ~50 ms = healthy 20 TPS. Mirrors Fabric.
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

    @Inject(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"))
    private void chunksmith$onTickConnection(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunksmith$runChunkSystemHousekeeping(booleanSupplier);
    }

    @Unique
    private void chunksmith$keepAwakeWhileGenerating() {
        if (ChunksmithProvider.isLoaded() && !ChunksmithProvider.get().getGenerationTasks().isEmpty()) {
            ((MinecraftServerAccess) (Object) this).setEmptyTicks(0);
            final long now = System.nanoTime();
            final long prev = this.chunksmith$lastTickNanos;
            this.chunksmith$lastTickNanos = now;
            if (prev != 0L) {
                final double dtMs = (now - prev) / 1.0e6D;
                if (dtMs > 0.0D && dtMs < 10_000.0D) {
                    this.chunksmith$mspt = (this.chunksmith$mspt * 0.8D) + (dtMs * 0.2D);
                }
            }
        } else {
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
                ((ChunkMapMixin) level.getChunkSource().chunkMap).invokeTick(() -> true);
                ((ServerChunkCacheMixin) level.getChunkSource()).invokeBroadcastChangedChunks(InactiveProfiler.INSTANCE);
                if (!ChunksmithNeoForge.ENABLE_MOONRISE_WORKAROUNDS) {
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
