package com.kishku7.chunksmith.lod.client.render;

import me.cortex.voxy.client.config.VoxyConfig;

/**
 * Reads voxy's ACTUAL configured render distance.
 *
 * <p>Hard-references voxy, so only ever loaded once voxy is known present (the same guard pattern as
 * {@link DhRadius}).
 *
 * <p>voxy stores this as {@code VoxyConfig.CONFIG.sectionRenderDistance}, measured in voxy SECTIONS.
 * A voxy section is 32 chunks = 512 blocks. Confirmed three ways in voxy 0.2.16-beta itself, not assumed:
 * <ul>
 *   <li>{@code HierarchicalOcclusionTraverser} uploads {@code pow(sectionRenderDistance * 16 * 32, 2)} as a
 *       squared BLOCK distance;</li>
 *   <li>{@code VoxyUniforms} exposes {@code round(sectionRenderDistance * 32)} as the Iris render-distance
 *       uniform, in CHUNKS;</li>
 *   <li>the config slider reads {@code round(srd * 16)} and its tooltip is "Render distance of voxy in
 *       chunks".</li>
 * </ul>
 * {@code VoxyRenderSystem.setRenderDistance} feeds the same field to the RenderDistanceTracker, so this IS
 * the radius voxy keeps and draws -- not a display-only number.
 *
 * <p>voxy's default is 16 sections = 8192 blocks, which is far beyond the 256-block protocol default we used
 * before we could read this. That gap is exactly the bug this class exists to close: a voxy player was being
 * sent the one region under their feet.
 *
 * <p><b>NEVER CALL THIS DURING MOD INIT.</b> Class-loading {@link VoxyConfig} from our client-init
 * entrypoint leaves voxy PERMANENTLY INERT -- it never logs "Initializing voxy instance", never creates its
 * render system, never ingests anything, and never says why. Proved by control run: same fixture, our jar
 * removed, voxy works. Call this only from the join handshake or later, by which time voxy has initialized
 * its own config and touching it is harmless.
 *
 * <p>Do NOT gate on {@code VoxyConfig.isRenderingEnabled()} either: it delegates to
 * {@code VoxyCommon.isAvailable()}, which tests a {@code FACTORY} field that is only assigned at
 * {@code RenderSystem.initRenderer} return -- so it reads false on any thread that asks too early, and we
 * would silently fall back to the 256-block default with voxy sitting right there. Read the config's own
 * fields instead; they are populated as soon as the class exists.
 */
public final class VoxyRadius {

    private VoxyRadius() {
    }

    /**
     * voxy's render distance in BLOCKS, or 0 if it cannot be read or voxy's renderer is switched off.
     *
     * <p>Call at handshake time, never at init -- see the class doc.
     */
    public static int blocks() {
        try {
            final VoxyConfig cfg = VoxyConfig.CONFIG;
            if (cfg == null || !cfg.enabled || !cfg.enableRendering || cfg.sectionRenderDistance <= 0f) {
                return 0;
            }
            return Math.round(cfg.sectionRenderDistance * 512.0f);
        } catch (final RuntimeException | LinkageError e) {
            // voxy present but incompatible. Fall back to the default rather than guess.
            return 0;
        }
    }
}
