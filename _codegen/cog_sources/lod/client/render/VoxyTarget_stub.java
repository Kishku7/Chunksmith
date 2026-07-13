package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.CsLodChunk;
import net.minecraft.world.level.Level;

/**
 * The voxy seam on every cell that has no voxy to feed -- so it does nothing, and says so.
 *
 * <p>This is the SEAM-CLASS pattern: same package, same class name, same public static signatures as the
 * real adapter, so {@code LodInjector} and {@code Renderers} -- which are SHARED source, built into every
 * jar -- compile and run unchanged everywhere.
 *
 * <p><b>Which cells get this stub, and why.</b> Two independent reasons, both hard:
 * <ol>
 *   <li><b>Every non-Fabric cell: it cannot compile.</b> voxy's
 *       {@code me.cortex.voxy.commonImpl.VoxyCommon} implements {@code net.fabricmc.api.ModInitializer}.
 *       Referencing it from a NeoForge or Forge build fails at javac with "cannot access ModInitializer" --
 *       the adapter is not portable, it is Fabric-bound at the type level.</li>
 *   <li><b>Fabric 1.20.1 and Fabric 1.21.1: there is nothing to feed.</b> Upstream voxy has NEVER published
 *       a build for either line -- its published set jumps 1.20.4 -&gt; 1.21.6 -- and the only 1.20.1/1.21.1
 *       voxy in existence is an unpublished source build of a fork. So on those cells no voxy can be
 *       installed, and pretending otherwise would be a lie in the manifest.</li>
 * </ol>
 *
 * <p>So these cells feed Distant Horizons, which DOES ship on every line and loader this mod targets. That
 * is the honest scope, and the manifests state it plainly.
 *
 * <p>{@link #supported()} returning false is load-bearing, not decorative: {@code Renderers.hasVoxy()} is
 * gated on it, so a client that somehow has a mod with the id {@code voxy} is never announced to the server
 * as a voxy client -- which would make the server ship LOD data at a voxy render distance for a renderer we
 * could not then inject into. Better to feed DH and be honest than to promise and drop.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/voxy. Edit ONLY there.
 */
public final class VoxyTarget {

    private VoxyTarget() {
    }

    /** False: there is no voxy adapter on this cell. See the class doc. */
    public static boolean supported() {
        return false;
    }

    /** Never available -- there is no voxy here to be available. */
    public static boolean available() {
        return false;
    }

    /**
     * Ingests nothing.
     *
     * @return 0 sections, always. Unreachable in practice: {@code LodInjector} only calls this when both
     *     {@code Renderers.hasVoxy()} and {@link #available()} are true, and both are false here.
     */
    public static int inject(final Level level, final CsLodChunk record) {
        return 0;
    }
}
