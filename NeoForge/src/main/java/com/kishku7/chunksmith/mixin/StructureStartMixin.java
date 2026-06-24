package com.kishku7.chunksmith.mixin;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
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
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow
    @Final
    private Structure structure;

    @Inject(method = "placeInChunk", at = @At("HEAD"))
    private void chunky$pushFaultContext(final WorldGenLevel level, final StructureManager structureManager,
                                         final ChunkGenerator generator, final RandomSource random,
                                         final BoundingBox chunkBB, final ChunkPos chunkPos, final CallbackInfo ci) {
        String id = null;
        try {
            final Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            final Identifier key = registry.getKey(this.structure);
            if (key != null) {
                id = key.toString();
            }
        } catch (final Throwable ignored) {
            // fall through with a null id - the fault is still suppressed + counted, just unattributed
        }
        StructureFaultReporter.get().pushContext(id, chunkPos.x(), chunkPos.z());
    }

    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void chunky$popFaultContext(final WorldGenLevel level, final StructureManager structureManager,
                                        final ChunkGenerator generator, final RandomSource random,
                                        final BoundingBox chunkBB, final ChunkPos chunkPos, final CallbackInfo ci) {
        StructureFaultReporter.get().popContext();
    }
}
