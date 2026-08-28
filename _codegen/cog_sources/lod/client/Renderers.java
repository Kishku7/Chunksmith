package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.LodWarnings;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import com.kishku7.chunksmith.lod.client.ClientPlatform;
import com.kishku7.chunksmith.lod.client.render.DhRadius;
import com.kishku7.chunksmith.lod.client.render.VoxyRadius;
import com.kishku7.chunksmith.lod.client.render.VoxyTarget;

/**
 * Which LOD renderer(s) the player actually has, and how far they are set to draw.
 *
 * <p>Mod ids: {@code voxy} (upstream and every fork) and {@code distanthorizons} (Fabric and NeoForge,
 * ships for 26.1.2 and 26.2). Every voxy fork we could reach keeps the id {@code voxy} and is identical to
 * upstream on the voxel layout, mapper scheme, section key, storage version, package root and ingest
 * signatures, so ONE adapter covers them all. That is a snapshot of the fork field as it stood on
 * 2026-07-13, and it is the roster the other voxy classes lean on: upstream, ggonzaDNG mia-edition,
 * NHblock714, Paulem79, srjefers and Vulkan-Voxy, each one run as a real jar. New forks keep appearing;
 * re-run the set before trusting the claim. The one place they DID drift -- the type of voxy's
 * render-distance config field -- is now read type-tolerantly; see {@code VoxyConfigReader}.
 *
 * <p><b>voxy is Fabric-only.</b> Its {@code VoxyCommon} implements {@code net.fabricmc.api.ModInitializer},
 * so the adapter cannot even COMPILE against a NeoForge build, and no fork has ever shipped for any 26.x
 * NeoForge. {@code VoxyTarget}/{@code VoxyRadius} are therefore per-loader SEAM classes, and
 * {@link #hasVoxy()} is gated on {@code VoxyTarget.supported()} so the NeoForge build never announces a
 * voxy it cannot feed.
 *
 * <p>Neither is bundled: voxy is All-Rights-Reserved and Distant Horizons is LGPL, so we compile against
 * them and never ship them.
 */
public final class Renderers {

    private static final String CAUSE_VOXY_SEAM = "voxy-seam-unloadable";

    private static final String CAUSE_DH_SEAM = "dh-seam-unloadable";

    private Renderers() {
    }

    public static boolean hasVoxy() {
        // Order matters: isModLoaded() short-circuits, so VoxyTarget (which hard-references voxy classes on
        // Fabric) is only ever class-loaded on a client that actually has voxy.
        return ClientPlatform.isModLoaded("voxy") && VoxyTarget.supported();
    }

    public static boolean hasDh() {
        return ClientPlatform.isModLoaded("distanthorizons");
    }

    public static boolean any() {
        return hasVoxy() || hasDh();
    }

    /**
     * How far the player's renderer is actually configured to draw, in blocks.
     *
     * <p>Use the renderer's configured LOD distance, whether it is LOWER or HIGHER than
     * {@link CsLodProtocol#DEFAULT_RADIUS_BLOCKS}, and fall back to that default only if neither renderer
     * can be read. With both installed, take the LARGER: the smaller renderer ignores what it cannot draw,
     * whereas shipping only the smaller radius leaves the further-drawing one with holes. DH reports
     * {@code graphics().chunkRenderDistance()} in chunks, voxy
     * {@code VoxyConfig.CONFIG.sectionRenderDistance} in 512-block sections.
     *
     * <p>The {@code LinkageError} catches below used to be silent {@code ignored} blocks, which is how a
     * re-typed voxy config field collapsed a player's radius from 8192 blocks to 256 with nothing in the
     * log (see {@code VoxyRadius}). Both readers announce their own failures now; these catches are the
     * last net, for our own seam class failing.
     */
    public static int configuredRadiusBlocks() {
        int blocks = 0;
        if (hasDh()) {
            try {
                blocks = Math.max(blocks, DhRadius.blocks());
            } catch (final LinkageError error) {
                // Not "DH is incompatible" -- DhRadius already reports that itself. This is OUR class
                // failing to load, which should be impossible. Never silent.
                LodWarnings.once(CAUSE_DH_SEAM,
                        "Distant Horizons is installed but Chunksmith could not load its own DH"
                                + " radius reader (" + error + "). Falling back to a LOD radius of "
                                + CsLodProtocol.DEFAULT_RADIUS_BLOCKS + " blocks. Please report this.");
            }
        }
        if (hasVoxy()) {
            try {
                blocks = Math.max(blocks, VoxyRadius.blocks());
            } catch (final LinkageError error) {
                LodWarnings.once(CAUSE_VOXY_SEAM,
                        "voxy is installed but Chunksmith could not load its own voxy radius reader ("
                                + error + "). Falling back to a LOD radius of "
                                + CsLodProtocol.DEFAULT_RADIUS_BLOCKS + " blocks, which is far less distant"
                                + " terrain than voxy can draw. Please report this, with your voxy version.");
            }
        }
        return blocks > 0 ? blocks : CsLodProtocol.DEFAULT_RADIUS_BLOCKS;
    }

    /**
     * For the status line at INIT. Deliberately does NOT read the radius: this line is logged from the
     * mod-init entrypoint, and asking voxy for its radius there class-loads {@code VoxyConfig} before voxy
     * has initialized, which leaves voxy permanently inert (see
     * {@link com.kishku7.chunksmith.lod.client.render.VoxyRadius}). The radius is logged at handshake time.
     */
    public static String describe() {
        if (!any()) {
            return "no LOD renderer installed";
        }
        return (hasVoxy() ? "voxy " : "") + (hasDh() ? "distant-horizons " : "") + "detected";
    }
}
