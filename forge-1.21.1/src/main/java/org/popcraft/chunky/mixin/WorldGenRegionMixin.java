package org.popcraft.chunky.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.popcraft.chunky.util.WorldgenOverreachReporter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/**
 * Captures vanilla "Detected setBlock in a far chunk" worldgen overreaches and routes them to
 * {@link WorldgenOverreachReporter} (aggregated single-line reports), while SUPPRESSING the
 * vanilla per-block log spam at its source. The block is still refused exactly as vanilla does
 * (ensureCanWrite still returns false) - only the logging is intercepted.
 * <p>
 * 1.21.1 ADAPTATION: the 1.21 worldgen refactor replaced the 1.20-era pair of fields
 * ({@code int writeRadiusCutoff} + {@code ChunkStatus generatingStatus}) with a single
 * {@code ChunkStep generatingStep} record (package {@code world.level.chunk.status}). The write
 * radius is now {@code generatingStep.blockStateWriteRadius()} and the target status is
 * {@code generatingStep.targetStatus()} - this matches the 26.x shape, so the 1.21 line uses the
 * ChunkStep form rather than the 1.20 two-field form. The far-write log call is still
 * {@code Util.logAndPauseIfInIde(String)} inside {@code WorldGenRegion.ensureCanWrite(BlockPos)},
 * so the @Redirect target is identical. {@code getCenter()} and the {@code currentlyGenerating}
 * supplier are unchanged.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Shadow
    @Final
    private ChunkStep generatingStep;
    @Shadow
    @Final
    private ServerLevel level;
    @Shadow
    private Supplier<String> currentlyGenerating;

    @Shadow
    public abstract ChunkPos getCenter();

    @Redirect(
            method = "ensureCanWrite",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;logAndPauseIfInIde(Ljava/lang/String;)V")
    )
    private void chunky$captureFarWrite(final String message, final BlockPos pos) {
        // Replaces the vanilla log call: record structured data, emit nothing here (spam gone).
        // ensureCanWrite still returns false on its own - only the log line is intercepted.
        try {
            final String feature = this.currentlyGenerating == null ? null : String.valueOf(this.currentlyGenerating.get());
            final String step = String.valueOf(this.generatingStep.targetStatus());
            final String dimension = this.level.dimension().location().toString();
            final ChunkPos center = this.getCenter();
            WorldgenOverreachReporter.get().record(
                    feature, step, dimension,
                    center.x, center.z,
                    SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ()),
                    pos.getY(), this.generatingStep.blockStateWriteRadius());
        } catch (final Throwable ignored) {
            // Diagnostics must never break worldgen.
        }
    }
}
