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
 * into structure templates whose saved anchor (TileX/TileY/TileZ) is more than 16 blocks from the
 * entity trigger it once each on fresh worldgen, flooding the log. The entity is still placed
 * correctly by {@code setPos} after load, so this is cosmetic noise - we intercept only the logging.
 * <p>
 * 1.21.1 ADAPTATION: on the 1.20.1 variant this targeted {@code HangingEntity} (the
 * {@code BlockAttachedEntity} superclass did not exist until 1.20.2) with the message
 * "Hanging entity at invalid position". From 1.20.2 onward the save logic + log moved UP to the new
 * {@code BlockAttachedEntity} superclass and the message became "Block-attached entity at invalid
 * position" - so the 1.21 line targets {@code BlockAttachedEntity} (the 26.x shape). The call is the
 * same {@code Logger.error(String, Object)}. There is still NO null/legacy missing-anchor branch -
 * {@code readAdditionalSaveData} reads TileX/Y/Z as ints and only logs the far-anchor case (> 16
 * blocks) - so this always records a non-missing (far) fault.
 */
@Mixin(BlockAttachedEntity.class)
public abstract class BlockAttachedEntityMixin {
    @Redirect(
            method = "readAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V")
    )
    private void chunky$captureInvalidPosition(final Logger logger, final String message, final Object storedPos) {
        try {
            // 1.21.1 only ever reaches this for the far-anchor case (anchor > 16 blocks away),
            // never a missing/legacy case, so missingAnchor is always false here.
            StructureFaultReporter.get().recordBlockAttached(false);
        } catch (final Throwable ignored) {
            // Diagnostics must never break entity loading / worldgen.
        }
    }
}
