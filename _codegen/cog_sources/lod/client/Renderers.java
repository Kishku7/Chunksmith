package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.LodWarnings;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import com.kishku7.chunksmith.lod.client.ClientPlatform;

/**
 * Which LOD renderer(s) the player actually has, and how far they are set to draw.
 *
 * <p>The mod ids we look for:
 * <ul>
 *   <li>{@code voxy} -- upstream, and every fork. Every fork we could reach keeps the id {@code voxy} and
 *       is identical to upstream on the voxel layout, the mapper scheme, the section key, the storage
 *       version, the package root and the ingest signatures, so ONE adapter covers them all -- no per-fork
 *       class-name table. Verified by running the real fork jars (upstream, ggonzaDNG mia-edition,
 *       NHblock714, Paulem79, srjefers, Vulkan-Voxy), 2026-07-13. The one place they DID drift -- the type
 *       of voxy's render-distance config field -- is now read type-tolerantly; see {@code VoxyConfigReader}.</li>
 *   <li>{@code distanthorizons} -- Fabric and NeoForge, ships for 26.1.2 and 26.2.</li>
 * </ul>
 *
 * <p><b>voxy is Fabric-only.</b> Upstream voxy is a Fabric mod -- its {@code VoxyCommon} literally
 * implements {@code net.fabricmc.api.ModInitializer}, so the adapter cannot even COMPILE against a NeoForge
 * build -- and no fork has ever shipped for any 26.x NeoForge. So {@code VoxyTarget}/{@code VoxyRadius} are
 * per-loader SEAM classes: the real adapter on Fabric, a documented no-op on NeoForge. {@link #hasVoxy()} is
 * gated on {@code VoxyTarget.supported()}, so the NeoForge build never announces a voxy it cannot feed. The
 * NeoForge jar drives Distant Horizons and nothing else, and the README says exactly that.</p>
 *
 * <p>Neither is bundled. Both are optional: voxy is All-Rights-Reserved and Distant Horizons is LGPL, so
 * we compile against them and never ship them.
 */
public final class Renderers {

    /** Warn keys -- one per cause, per session. See {@link LodWarnings}. */
    private static final String CAUSE_VOXY_SEAM = "voxy-seam-unloadable";

    private static final String CAUSE_DH_SEAM = "dh-seam-unloadable";

    private Renderers() {
    }

    public static boolean hasVoxy() {
        // Order matters: isModLoaded() short-circuits, so VoxyTarget (which hard-references voxy classes on
        // Fabric) is only ever class-loaded on a client that actually has voxy.
        return ClientPlatform.isModLoaded("voxy") && com.kishku7.chunksmith.lod.client.render.VoxyTarget.supported();
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
     * <p>The server follows this number -- it is the client that knows how far it can draw, and sending
     * more than that is wasted bandwidth while sending less leaves visible holes. The rule: use the
     * renderer's configured LOD distance; if it cannot be determined, default to
     * {@link CsLodProtocol#DEFAULT_RADIUS_BLOCKS}; and follow the client's setting whether it is LOWER or
     * HIGHER than that default.
     *
     * <p>Both renderers expose it: Distant Horizons as {@code graphics().chunkRenderDistance()} (in chunks),
     * voxy as {@code VoxyConfig.CONFIG.sectionRenderDistance} (in 512-block sections). When BOTH are
     * installed we take the LARGER of the two -- the smaller renderer simply ignores what it cannot draw,
     * whereas shipping only the smaller radius would leave the further-drawing renderer with holes.
     *
     * <p>Only if neither can be read do we fall back to {@link CsLodProtocol#DEFAULT_RADIUS_BLOCKS}. That
     * default was previously used for EVERY voxy player, because voxy's distance was thought to be
     * unreadable -- and at 256 blocks (barely past vanilla view distance) it meant a voxy client was sent
     * one region and drew almost nothing. Read the real number; never invent one.
     *
     * <p><b>And when the number cannot be read, SAY SO.</b> The {@code LinkageError} catches below used to
     * be silent {@code ignored} blocks. A voxy fork that re-typed one config field therefore collapsed a
     * player's radius from 8192 blocks to 256 with nothing in the log -- 32x less terrain, reported as
     * success. Both radius readers now announce their own failures (see {@code VoxyRadius} / {@code
     * DhTarget.disable}); these catches are the last net, for the case where our own seam class cannot even
     * be loaded, and they are loud too.
     */
    public static int configuredRadiusBlocks() {
        int blocks = 0;
        if (hasDh()) {
            try {
                blocks = Math.max(blocks, com.kishku7.chunksmith.lod.client.render.DhRadius.blocks());
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
                blocks = Math.max(blocks, com.kishku7.chunksmith.lod.client.render.VoxyRadius.blocks());
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
     * For the status line at INIT.
     *
     * <p>Deliberately does NOT read the radius. This line is logged from the mod-init entrypoint, and
     * asking voxy for its radius there class-loads {@code VoxyConfig} before voxy has initialized -- which
     * leaves voxy permanently inert (see {@link com.kishku7.chunksmith.lod.client.render.VoxyRadius}). The radius
     * is logged at handshake time instead, where reading it is both safe and correct.
     */
    public static String describe() {
        if (!any()) {
            return "no LOD renderer installed";
        }
        return (hasVoxy() ? "voxy " : "") + (hasDh() ? "distant-horizons " : "") + "detected";
    }
}
