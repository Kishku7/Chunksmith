package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import com.kishku7.chunksmith.lod.client.ClientPlatform;

/**
 * Which LOD renderer(s) the player actually has, and how far they are set to draw.
 *
 * <p>The mod ids we look for:
 * <ul>
 *   <li>{@code voxy} -- upstream, and every fork. All five known forks are byte-identical to upstream on
 *       the voxel layout, the mapper scheme, the section key, the storage version, the package root and
 *       the ingest signatures, so ONE adapter covers them all -- no per-fork class-name table. (None
 *       support 26.1.2 yet; the id is checked anyway so a fork "just works" the day it does.)</li>
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
     */
    public static int configuredRadiusBlocks() {
        int blocks = 0;
        if (hasDh()) {
            try {
                blocks = Math.max(blocks, com.kishku7.chunksmith.lod.client.render.DhRadius.blocks());
            } catch (final LinkageError ignored) {
                // DH present but incompatible -- ignore it and try the other renderer.
            }
        }
        if (hasVoxy()) {
            try {
                blocks = Math.max(blocks, com.kishku7.chunksmith.lod.client.render.VoxyRadius.blocks());
            } catch (final LinkageError ignored) {
                // voxy present but incompatible -- ignore it.
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
