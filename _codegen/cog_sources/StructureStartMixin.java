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

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
//[[[cog
// import cog, compat
// # The structure-key type moved/renamed at 26 (resources.ResourceLocation -> resources.Identifier).
// # Cog picks the import that exists on the target runtime.
// if compat.dimension_identifier_call(mcver) == "identifier":
//     cog.outl("import net.minecraft.resources.Identifier;")
// else:
//     cog.outl("import net.minecraft.resources.ResourceLocation;")
//]]]
//[[[end]]]
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures which structure (and chunk) is being placed, so
 * worldgen faults raised deep inside placement - notably the
 * "Block-attached entity at invalid position" error
 * intercepted by {@link BlockAttachedEntityMixin} - can be
 * attributed to the owning datapack/mod. {@code placeInChunk}
 * runs synchronously on the worldgen worker thread and all
 * piece/entity placement happens within it, so a ThreadLocal
 * pushed at HEAD and popped at RETURN is live for every fault
 * fired during the placement.
 *
 * <p>COG DRIFT: structure-key type ResourceLocation
 * (&lt;=1.21.10) vs Identifier (1.21.11/26); ChunkPos x/z as
 * fields (&lt;=1.21.10) vs methods x()/z(); and
 * registryOrThrow (&lt;=1.21.1) vs lookupOrThrow
 * (&gt;=1.21.4). The name must switch because on the older
 * lines lookupOrThrow returns a RegistryLookup, not a
 * Registry. All Cog-emitted.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow
    @Final
    private Structure structure;

    @Inject(method = "placeInChunk", at = @At("HEAD"), require = 0)
    private void chunksmith$pushFaultContext(final WorldGenLevel level, final StructureManager structureManager,
                                         final ChunkGenerator generator, final RandomSource random,
                                         final BoundingBox chunkBB, final ChunkPos chunkPos, final CallbackInfo ci) {
        String id = null;
        try {
            //[[[cog
            // import cog, compat
            // cog.outl('final Registry<Structure> registry = level.registryAccess().%s(Registries.STRUCTURE);'
            //          % compat.registry_lookup_call(mcver))
            //]]]
            //[[[end]]]
            //[[[cog
            // import cog, compat
            // keytype = "Identifier" if compat.dimension_identifier_call(mcver) == "identifier" else "ResourceLocation"
            // cog.outl("final %s key = registry.getKey(this.structure);" % keytype)
            //]]]
            //[[[end]]]
            if (key != null) {
                id = key.toString();
            }
        } catch (Throwable ignored) {
            // fall through with a null id - the fault is still suppressed + counted, just unattributed
        }
        //[[[cog
        // import cog, compat
        // cog.outl("StructureFaultReporter.get().pushContext(id, chunkPos.%s, chunkPos.%s);"
        //          % (compat.chunkpos_x(mcver), compat.chunkpos_z(mcver)))
        //]]]
        //[[[end]]]
    }

    @Inject(method = "placeInChunk", at = @At("RETURN"), require = 0)
    private void chunksmith$popFaultContext(final WorldGenLevel level, final StructureManager structureManager,
                                        final ChunkGenerator generator, final RandomSource random,
                                        final BoundingBox chunkBB, final ChunkPos chunkPos, final CallbackInfo ci) {
        StructureFaultReporter.get().popContext();
    }
}
