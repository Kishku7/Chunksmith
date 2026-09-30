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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
//[[[cog
// import cog, compat
// era = compat.horizon_save_era(mcver)
// if era in ("u1", "m", "s"):
//     cog.outl("import java.util.concurrent.CompletableFuture;")
// if era in ("m", "s"):
//     cog.outl("import java.util.function.Supplier;")
// cog.outl("import net.minecraft.world.level.chunk.storage.%s;" % ("SimpleRegionStorage" if era == "s" else "ChunkStorage"))
//]]]
//[[[end]]]
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//[[[cog
// import cog, compat
// if compat.horizon_save_era(mcver) == "u0":
//     cog.outl("import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;")
// else:
//     cog.outl("import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;")
//]]]
//[[[end]]]

/**
 * The LOD horizon's backstop (mod_support #39): refuse the chunk write itself. Vanilla never gets here for a
 * ring chunk -- {@link ChunkMapHorizonSaveMixin} stops it first -- but C2ME's chunk system saves on unload
 * by serializing the chunk and calling this write directly, skipping ChunkMap.save. Its callers have already
 * marked the chunk saved, so answering with a completed write cannot spin. The target is the storage write,
 * never IOWorker.store: C2ME replaces the IOWorker with its own subclass.
 */
//[[[cog
// import cog, compat
// era = compat.horizon_save_era(mcver)
// cog.outl("@Mixin(%s.class)" % ("SimpleRegionStorage" if era == "s" else "ChunkStorage"))
//]]]
//[[[end]]]
public abstract class StorageWriteHorizonMixin {

    //[[[cog
    // import cog, compat
    // era = compat.horizon_save_era(mcver)
    // if era == "u0":
    //     cog.outl('    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"), cancellable = true)')
    //     cog.outl("    private void chunksmith$horizonVeto(ChunkPos pos, CompoundTag tag, CallbackInfo ci) {")
    //     cog.outl("        if (chunksmith$drop(pos)) {")
    //     cog.outl("            ci.cancel();")
    //     cog.outl("        }")
    //     cog.outl("    }")
    // else:
    //     if era == "u1":
    //         cog.outl('    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)')
    //         cog.outl("    private void chunksmith$horizonVeto(ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {")
    //     else:
    //         cog.outl('    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)')
    //         cog.outl("    private void chunksmith$horizonVeto(ChunkPos pos, Supplier<CompoundTag> tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {")
    //     cog.outl("        if (chunksmith$drop(pos)) {")
    //     cog.outl("            cir.setReturnValue(CompletableFuture.completedFuture(null));")
    //     cog.outl("        }")
    //     cog.outl("    }")
    //]]]
    //[[[end]]]

    private boolean chunksmith$drop(ChunkPos pos) {
        if (!HorizonGuard.armed() || !((Object) this instanceof ChunkMap)) {
            return false;
        }
        return HorizonGuard.drop(((ChunkMapLevelAccessor) (Object) this).chunksmith$horizonLevel(), pos);
    }
}
