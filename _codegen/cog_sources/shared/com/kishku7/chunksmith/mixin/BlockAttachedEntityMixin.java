/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.mixin;

import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses vanilla's "Block-attached entity at invalid
 * position: {}" error and routes it to {@link
 * StructureFaultReporter} instead. The log call is in {@code
 * BlockAttachedEntity.readAdditionalSaveData}; item frames /
 * paintings / leash knots baked into structure templates
 * without a modern {@code block_pos} tag (e.g. legacy {@code
 * TileX/TileY/TileZ}) trigger it once each on fresh worldgen,
 * flooding the log. The entity is still placed correctly by
 * {@code setPos} after load, so this is cosmetic noise - we
 * intercept only the logging. <p> A {@code null} stored
 * position is the missing-anchor (legacy-format) case; a
 * non-null BlockPos means the saved anchor is more than 16
 * blocks from the entity.
 */
@Mixin(BlockAttachedEntity.class)
public abstract class BlockAttachedEntityMixin {
    @Redirect(
            method = "readAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V", remap = false),
            require = 0
    )
    private void chunksmith$captureInvalidPosition(Logger logger, String message, Object storedPos) {
        try {
            StructureFaultReporter.get().recordBlockAttached(storedPos == null);
        } catch (Throwable ignored) {
        }
    }
}
