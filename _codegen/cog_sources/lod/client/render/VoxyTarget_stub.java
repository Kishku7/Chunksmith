package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.CsLodChunk;
import net.minecraft.world.level.Level;

/**
 * The voxy seam on every cell that has no voxy to feed -- so it does nothing, and says so.
 *
 * <p><b>Which cells get this stub, and why.</b> Two independent reasons, both hard. (1) Every non-Fabric
 * cell: it cannot compile -- voxy's {@code me.cortex.voxy.commonImpl.VoxyCommon} implements
 * {@code net.fabricmc.api.ModInitializer}, so referencing it from a NeoForge or Forge build fails at javac
 * with "cannot access ModInitializer". (2) Fabric 1.20.1 and Fabric 1.21.1: there is nothing to feed --
 * upstream voxy has NEVER published a build for either line (its published set jumps 1.20.4 -&gt; 1.21.6),
 * and the only 1.20.1/1.21.1 voxy in existence is an unpublished source build of a fork.
 *
 * <p>{@link #supported()} returning false is load-bearing: {@code Renderers.hasVoxy()} is gated on it, so a
 * client that somehow has a mod with the id {@code voxy} is never announced to the server as a voxy client
 * whose render distance the server would then ship LOD data for. These cells feed Distant Horizons instead.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/voxy. Edit ONLY there.
 */
public final class VoxyTarget {

    private VoxyTarget() {
    }

    public static boolean supported() {
        return false;
    }

    public static boolean available() {
        return false;
    }

    /** @return 0 always; unreachable, as {@code Renderers.hasVoxy()} and {@link #available()} are both false. */
    public static int inject(final Level level, final CsLodChunk record) {
        return 0;
    }
}
