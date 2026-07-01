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
 * Captures which structure (and chunk) is currently being placed, so that worldgen faults raised
 * deep inside placement - notably the vanilla "Block-attached entity at invalid position" error,
 * intercepted by {@link BlockAttachedEntityMixin} - can be attributed to the owning datapack/mod.
 * <p>
 * {@code StructureStart.placeInChunk} runs synchronously on the worldgen worker thread and all
 * piece/entity placement happens within it, so a ThreadLocal pushed at HEAD and popped at RETURN
 * is live for every fault that fires during the placement.
 *
 * <p>COG DRIFT: the structure-key type is resources.ResourceLocation (&lt;=1.21.10) vs
 * resources.Identifier (1.21.11/26), and ChunkPos exposes x/z as fields (&lt;=1.21.10) vs methods
 * x()/z() (1.21.11/26). Both are Cog-emitted so one source compiles on every runtime.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow
    @Final
    private Structure structure;

    @Inject(method = "placeInChunk", at = @At("HEAD"))
    private void chunksmith$pushFaultContext(final WorldGenLevel level, final StructureManager structureManager,
                                         final ChunkGenerator generator, final RandomSource random,
                                         final BoundingBox chunkBB, final ChunkPos chunkPos, final CallbackInfo ci) {
        String id = null;
        try {
            final Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            //[[[cog
            // import cog, compat
            // keytype = "Identifier" if compat.dimension_identifier_call(mcver) == "identifier" else "ResourceLocation"
            // cog.outl("final %s key = registry.getKey(this.structure);" % keytype)
            //]]]
            //[[[end]]]
            if (key != null) {
                id = key.toString();
            }
        } catch (final Throwable ignored) {
            // fall through with a null id - the fault is still suppressed + counted, just unattributed
        }
        //[[[cog
        // import cog, compat
        // cog.outl("StructureFaultReporter.get().pushContext(id, chunkPos.%s, chunkPos.%s);"
        //          % (compat.chunkpos_x(mcver), compat.chunkpos_z(mcver)))
        //]]]
        //[[[end]]]
    }

    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void chunksmith$popFaultContext(final WorldGenLevel level, final StructureManager structureManager,
                                        final ChunkGenerator generator, final RandomSource random,
                                        final BoundingBox chunkBB, final ChunkPos chunkPos, final CallbackInfo ci) {
        StructureFaultReporter.get().popContext();
    }
}
