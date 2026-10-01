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

import com.kishku7.chunksmith.lod.HorizonGuard;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
//[[[cog
// import cog, compat
// if compat.horizon_save_era(mcver) in ("u0", "u1"):
//     cog.outl("import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;")
//     cog.outl("import net.minecraft.core.SectionPos;")
//     cog.outl("import net.minecraft.world.level.LevelHeightAccessor;")
//     cog.outl("import org.spongepowered.asm.mixin.Final;")
//     cog.outl("import org.spongepowered.asm.mixin.Shadow;")
//]]]
//[[[end]]]
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The LOD horizon's POI veto (mod_support #39). Only the POI manager of a level with an armed horizon can
 * match; every other section storage passes straight on.
 *
 * <p>Cancelling the write is only half of it: the column's DIRTY marks have to go too. From 1.21.2 the POI
 * tick removes the column from its dirty set before it calls the write, so a plain cancel is enough. Up to
 * 1.21.1 the marks are removed INSIDE writeColumn, and the tick is {@code while (hasWork() && haveTime)}
 * on the first dirty column -- so a bare cancel left the column dirty and the tick retried it until the
 * tick ran out of time, every tick. That starved generation to ~7 chunks/s (from ~56) and slowed the
 * shutdown flush to 97 s on 1.21.1. Those eras clear the marks here, exactly as writeColumn would.
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageHorizonMixin {

    //[[[cog
    // import cog, compat
    // if compat.horizon_save_era(mcver) in ("u0", "u1"):
    //     cog.outl("    @Shadow")
    //     cog.outl("    @Final")
    //     cog.outl("    private LongLinkedOpenHashSet dirty;")
    //     cog.outl("")
    //     cog.outl("    @Shadow")
    //     cog.outl("    @Final")
    //     cog.outl("    protected LevelHeightAccessor levelHeightAccessor;")
    //     cog.outl("")
    //     cog.outl('    @Inject(method = "writeColumn(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"), cancellable = true)')
    //     cog.outl("    private void chunksmith$horizonVeto(ChunkPos pos, CallbackInfo ci) {")
    //     cog.outl("        if (HorizonGuard.armed() && HorizonGuard.dropPoi(this, pos)) {")
    //     cog.outl("            for (int y = this.levelHeightAccessor.getMinSection(); y < this.levelHeightAccessor.getMaxSection(); y++) {")
    //     cog.outl("                this.dirty.remove(SectionPos.asLong(pos.x, y, pos.z));")
    //     cog.outl("            }")
    //     cog.outl("            ci.cancel();")
    //     cog.outl("        }")
    //     cog.outl("    }")
    // else:
    //     cog.outl('    @Inject(method = "writeChunk(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"), cancellable = true)')
    //     cog.outl("    private void chunksmith$horizonVeto(ChunkPos pos, CallbackInfo ci) {")
    //     cog.outl("        if (HorizonGuard.armed() && HorizonGuard.dropPoi(this, pos)) {")
    //     cog.outl("            ci.cancel();")
    //     cog.outl("        }")
    //     cog.outl("    }")
    //]]]
    //[[[end]]]
}
