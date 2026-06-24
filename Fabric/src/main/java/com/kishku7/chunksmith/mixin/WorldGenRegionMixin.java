package com.kishku7.chunksmith.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStep;
import com.kishku7.chunksmith.util.WorldgenOverreachReporter;
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
 * VERSION PORTABILITY: this mixin only references members that exist across the entire
 * 26.1.0 -> 26.2-rc-1 range and binds to {@code WorldGenRegion.ensureCanWrite(BlockPos)}, whose
 * far-write {@code Util.logAndPauseIfInIde(String)} call and message format are byte-for-byte
 * identical in both the 26.1 and 26.2 lines (verified against decompiled source 2026-06-11).
 * It deliberately avoids the {@code centerChunkX/centerChunkZ/writeRadius} fields, which are NEW
 * in 26.2-rc-1 and absent before it - the same data is read instead through {@link #getCenter()}
 * and {@code generatingStep.blockStateWriteRadius()}, both present on every targeted version. One
 * source therefore compiles and applies for the whole range with no per-version branches.
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
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;logAndPauseIfInIde(Ljava/lang/String;)V")
    )
    private void chunksmith$captureFarWrite(final String message, final BlockPos pos) {
        // Replaces the vanilla log call: record structured data, emit nothing here (spam gone).
        // ensureCanWrite still returns false on its own - only the log line is intercepted.
        try {
            final String feature = this.currentlyGenerating == null ? null : String.valueOf(this.currentlyGenerating.get());
            final String step = String.valueOf(this.generatingStep.targetStatus());
            final String dimension = this.level.dimension().identifier().toString();
            final ChunkPos center = this.getCenter();
            final int writeRadius = this.generatingStep.blockStateWriteRadius();
            WorldgenOverreachReporter.get().record(
                    feature, step, dimension,
                    center.x(), center.z(),
                    SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ()),
                    pos.getY(), writeRadius);
        } catch (final Throwable ignored) {
            // Diagnostics must never break worldgen.
        }
    }
}
