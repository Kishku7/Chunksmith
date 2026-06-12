package org.popcraft.chunky.mixin;

import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import org.popcraft.chunky.util.StructureFaultReporter;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses vanilla's "Block-attached entity at invalid position: {}" error and routes it to
 * {@link StructureFaultReporter} instead. The log call is in
 * {@code BlockAttachedEntity.readAdditionalSaveData}; item frames / paintings / leash knots baked
 * into structure templates without a modern {@code block_pos} tag (e.g. legacy {@code TileX/TileY/TileZ})
 * trigger it once each on fresh worldgen, flooding the log. The entity is still placed correctly by
 * {@code setPos} after load, so this is cosmetic noise - we intercept only the logging.
 * <p>
 * A {@code null} stored position is the missing-anchor (legacy-format) case; a non-null BlockPos
 * means the saved anchor is more than 16 blocks from the entity.
 */
@Mixin(BlockAttachedEntity.class)
public abstract class BlockAttachedEntityMixin {
    @Redirect(
            method = "readAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V")
    )
    private void chunky$captureInvalidPosition(final Logger logger, final String message, final Object storedPos) {
        try {
            StructureFaultReporter.get().recordBlockAttached(storedPos == null);
        } catch (final Throwable ignored) {
            // Diagnostics must never break entity loading / worldgen.
        }
    }
}
