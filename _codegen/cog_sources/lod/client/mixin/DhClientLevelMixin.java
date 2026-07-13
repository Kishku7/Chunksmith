package com.kishku7.chunksmith.lod.client.mixin;

import com.kishku7.chunksmith.lod.client.render.DhPushGuard;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops Distant Horizons from silently DISCARDING the LOD data we push it.
 *
 * <p>On a DH-enabled server with real-time updates on (the default),
 * {@code DhClientLevel.shouldProcessChunkUpdate} drops any chunk update for a position DH has seen in the
 * last ten minutes -- and {@code overwriteChunkDataAsync} still returns success. So our pushes vanish and
 * every counter we own says they landed. See {@link DhPushGuard} for the exact code path.
 *
 * <p>This forces the gate open for OUR pushes only, marked by a thread-local flag. Every other chunk update
 * -- real player edits, DH's own traffic -- takes DH's unmodified path, dedupe and all.
 *
 * <p>{@code remap = false} because {@code com.seibel.*} is a plain library, not Minecraft: there are no
 * intermediary mappings to apply, and the method is public, so no access widener is needed.
 *
 * <p>The target is named as a CLASS, not a {@code targets = "..."} string. Both compile to the same type
 * name in the annotation's bytecode -- Mixin reads it as a string off the ASM node and never loads the
 * class -- but the class form is checked by javac, so a DH refactor that moved this class would fail the
 * BUILD instead of silently no-op'ing at runtime. (It is also what the Mixin annotation processor asks
 * for: a public target given as a string is a warning on the SRG Forge cell.) DH is a compileOnly
 * dependency of every cell, so the type is always on the compile classpath and never in the jar.
 *
 * <p>The config is registered on BOTH loaders -- Fabric's {@code fabric.mod.json} {@code "mixins"} array and
 * NeoForge's {@code neoforge.mods.toml} {@code [[mixins]] config = ...} -- so this mixin applies wherever DH
 * does, which is both.
 *
 * <p><b>Distant Horizons is an OPTIONAL soft dependency, so {@code chunksmith_lodclient.mixins.json} MUST stay
 * {@code "required": false}.</b> Mixin resolves a config's target classes at PREPARE time: on a client with
 * no DH installed this target does not exist, and in a config marked {@code required: true} that missing
 * class is a FATAL bootstrap error -- it would take the game down for every voxy-only player, who is a fully
 * supported configuration. With {@code required: false} Mixin logs it and skips the mixin, which is the
 * intended behaviour for a soft dependency. The injector itself stays at the default {@code require = 1}
 * (see the mixin config): if DH IS present and this method has moved, that is a real breakage and it is
 * reported loudly rather than silently no-op'ing.
 */
@Mixin(value = DhClientLevel.class, remap = false)
public class DhClientLevelMixin {

    @Inject(method = "shouldProcessChunkUpdate", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksmith$alwaysAcceptOurPushes(final CallbackInfoReturnable<Boolean> cir) {
        if (DhPushGuard.isPushing()) {
            DhPushGuard.forced();
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
