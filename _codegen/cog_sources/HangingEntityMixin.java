package com.kishku7.chunksmith.mixin;

import net.minecraft.world.entity.decoration.HangingEntity;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses vanilla's "Hanging entity at invalid position: {}" error and routes it to
 * {@link StructureFaultReporter} instead. This is the 1.20.* form of the invalid-position log
 * suppressor: on 1.20.* the owning class is {@code HangingEntity} (the {@code BlockAttachedEntity}
 * superclass that carries this logic from 1.20.2 / 1.21 onward does not exist yet), and the log call
 * is the same {@code Logger.error(String, Object)}. From 1.21 onward this file is replaced by
 * {@link BlockAttachedEntityMixin} (same @Redirect body, {@code @Mixin(BlockAttachedEntity)}) -- the
 * presence swap is driven by cog-gen keyed on compat.hanging_entity_class (1.20.* -> HangingEntity,
 * 1.21.*+ -> BlockAttachedEntity). Item frames / paintings / leash knots baked into structure
 * templates trigger it once each on fresh worldgen, flooding the log; the entity is still placed
 * correctly by {@code setPos} after load, so this is cosmetic noise - we intercept only the logging.
 * <p>
 * A {@code null} stored position is the missing-anchor (legacy-format) case; a non-null BlockPos
 * means the saved anchor is more than 16 blocks from the entity.
 */
@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {
    @Redirect(
            method = "readAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V", remap = false)
    )
    private void chunksmith$captureInvalidPosition(final Logger logger, final String message, final Object storedPos) {
        try {
            StructureFaultReporter.get().recordBlockAttached(storedPos == null);
        } catch (final Throwable ignored) {
            // Diagnostics must never break entity loading / worldgen.
        }
    }
}
