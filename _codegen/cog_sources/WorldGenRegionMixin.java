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
 *
 * <p>COG DRIFT: the far-write log call is Util.logAndPauseIfInIde(String), whose owning class
 * moved package at 1.21.11 (net.minecraft.Util -> net.minecraft.util.Util). That is a STRING inside
 * a mixin annotation, so a reflection facade cannot touch it; the @At target descriptor is emitted
 * by Cog (compat.util_at_target). The dimension-id accessor (location()/identifier()) and the
 * ChunkPos coord access (field x/z vs method x()/z()) also drift and are Cog-emitted.
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
            //[[[cog
            // import cog, compat
            // cog.outl('at = @At(value = "INVOKE", target = "%s")' % compat.util_at_target(mcver))
            //]]]
            //[[[end]]]
    )
    private void chunksmith$captureFarWrite(final String message, final BlockPos pos) {
        // Replaces the vanilla log call: record structured data, emit nothing here (spam gone).
        // ensureCanWrite still returns false on its own - only the log line is intercepted.
        try {
            final String feature = this.currentlyGenerating == null ? null : String.valueOf(this.currentlyGenerating.get());
            final String step = String.valueOf(this.generatingStep.targetStatus());
            //[[[cog
            // import cog, compat
            // cog.outl('final String dimension = this.level.dimension().%s().toString();' % compat.dimension_identifier_call(mcver))
            //]]]
            //[[[end]]]
            final ChunkPos center = this.getCenter();
            final int writeRadius = this.generatingStep.blockStateWriteRadius();
            //[[[cog
            // import cog, compat
            // cog.outl('WorldgenOverreachReporter.get().record(')
            // cog.outl('        feature, step, dimension,')
            // cog.outl('        center.%s, center.%s,' % (compat.chunkpos_x(mcver), compat.chunkpos_z(mcver)))
            // cog.outl('        SectionPos.blockToSectionCoord(pos.getX()),')
            // cog.outl('        SectionPos.blockToSectionCoord(pos.getZ()),')
            // cog.outl('        pos.getY(), writeRadius);')
            //]]]
            //[[[end]]]
        } catch (final Throwable ignored) {
            // Diagnostics must never break worldgen.
        }
    }
}
