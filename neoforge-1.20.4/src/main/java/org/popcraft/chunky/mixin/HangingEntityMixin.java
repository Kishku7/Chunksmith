package org.popcraft.chunky.mixin;

import net.minecraft.world.entity.decoration.HangingEntity;
import org.popcraft.chunky.util.StructureFaultReporter;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses vanilla's "Hanging entity at invalid position: {}" error and routes it to
 * {@link StructureFaultReporter} instead. The log call is in
 * {@code HangingEntity.readAdditionalSaveData}; item frames / paintings / leash knots baked into
 * structure templates whose saved anchor (TileX/TileY/TileZ) is more than 16 blocks from the
 * entity trigger it once each on fresh worldgen, flooding the log. The entity is still placed
 * correctly by {@code setPos} after load, so this is cosmetic noise - we intercept only the logging.
 * <p>
 * 1.20.1 ADAPTATION: on 1.20.1 the class is {@code HangingEntity} (the 26.x {@code BlockAttachedEntity}
 * does not exist until 1.20.2), the log message is "Hanging entity at invalid position" (not
 * "Block-attached entity..."), and the call is the same {@code Logger.error(String, Object)}. There
 * is NO null/legacy missing-anchor branch on 1.20.1 - {@code readAdditionalSaveData} always reads
 * TileX/TileY/TileZ as ints and only logs the far-anchor case - so this always records a non-missing
 * (far) fault.
 */
@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {
    @Redirect(
            method = "readAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V")
    )
    private void chunky$captureInvalidPosition(final Logger logger, final String message, final Object storedPos) {
        try {
            // 1.20.1 only ever reaches this for the far-anchor case (anchor > 16 blocks away),
            // never the missing/legacy case, so missingAnchor is always false here.
            StructureFaultReporter.get().recordBlockAttached(false);
        } catch (final Throwable ignored) {
            // Diagnostics must never break entity loading / worldgen.
        }
    }
}
