package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.LodWarnings;
import com.kishku7.chunksmith.lod.client.VoxyConfigReader;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import me.cortex.voxy.client.config.VoxyConfig;

/**
 * Reads voxy's ACTUAL configured render distance -- upstream's, or any fork's.
 *
 * <p>Hard-references the voxy CLASS (never its fields), so it is only ever loaded once voxy is known
 * present -- the same guard pattern as {@link DhRadius}.
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
 * <p><b>Why the read goes through {@link VoxyConfigReader} instead of touching the field directly.</b>
 * Because the field's TYPE drifts across forks, and a compiled field access does not survive that. Upstream
 * declares {@code float sectionRenderDistance}; the srjefers fork (rebased off voxy 0.2.8-alpha, which used
 * an int) ships {@code public int sectionRenderDistance}. Our compiled {@code getfield ...:F} does not
 * resolve against an {@code I} field, so the JVM throws {@code NoSuchFieldError} -- which the old
 * {@code catch (LinkageError)} in this very method swallowed, returned 0, and dropped the player's radius
 * from 8192 blocks to 256. A 32x collapse, reported as success. Proven on the real fork jar, 2026-07-13.
 * Now the field is looked up by NAME and read as whatever numeric type it actually is, and a field we
 * genuinely cannot read is ANNOUNCED (see {@link LodWarnings}), never swallowed.
 *
 * <p>Reflection is used HERE and nowhere else in the voxy adapter. {@code VoxelIngestService.rawIngest},
 * {@code VoxyCommon} and {@code WorldIdentifier} were checked with {@code javap} against upstream and every
 * reachable fork and are identical, so {@link VoxyTarget} calls them directly -- reflection there would cost
 * a lookup per chunk and buy nothing. Reflection where drift is PROVEN; direct calls where stability is
 * proven.
 *
 * <p><b>NEVER CALL THIS DURING MOD INIT.</b> Class-loading {@link VoxyConfig} from our client-init
 * entrypoint leaves voxy PERMANENTLY INERT -- it never logs "Initializing voxy instance", never creates its
 * render system, never ingests anything, and never says why. Proved by control run: same fixture, our jar
 * removed, voxy works. Call this only from the join handshake or later, by which time voxy has initialized
 * its own config and touching it is harmless. Reflection does NOT change this: looking a field up still
 * initializes the class that declares it.
 *
 * <p>Do NOT gate on {@code VoxyConfig.isRenderingEnabled()} either: it delegates to
 * {@code VoxyCommon.isAvailable()}, which tests a {@code FACTORY} field that is only assigned at
 * {@code RenderSystem.initRenderer} return -- so it reads false on any thread that asks too early, and we
 * would silently fall back to the 256-block default with voxy sitting right there. Read the config's own
 * fields instead; they are populated as soon as the class exists.
 */
public final class VoxyRadius {

    /** Warn key: voxy is installed, but its config class is not a shape any voxy we know of has. */
    private static final String CAUSE_CONFIG = "voxy-config-unreadable";

    private VoxyRadius() {
    }

    /**
     * voxy's render distance in BLOCKS, or 0 if it cannot be read or voxy's renderer is switched off.
     *
     * <p>Call at handshake time, never at init -- see the class doc.
     */
    public static int blocks() {
        final Object config;
        try {
            // Even the holder is fetched by NAME: a fork that renamed or removed CONFIG degrades to
            // "no config" instead of throwing a NoSuchFieldError out of our own bytecode.
            config = VoxyConfigReader.staticField(VoxyConfig.class, "CONFIG");
        } catch (final RuntimeException | LinkageError e) {
            // The voxy that is installed is not the shape we compiled against at all (config class renamed,
            // moved, or gone). Say so -- once -- and name the radius the player is actually going to get.
            LodWarnings.once(CAUSE_CONFIG,
                    "voxy is installed but its configuration could not be read (" + e + ")."
                            + " Falling back to a LOD radius of " + CsLodProtocol.DEFAULT_RADIUS_BLOCKS
                            + " blocks instead of the distance voxy is actually set to, so you will see far"
                            + " less distant terrain than voxy can draw. This normally means a voxy fork"
                            + " whose config class differs from upstream. Please report it, with your voxy"
                            + " version.");
            return 0;
        }
        // Type-tolerant (float / int / double / long), and it warns for itself when the field is gone.
        // Quiet when voxy is simply switched off -- that is the player's choice, not a fault.
        return VoxyConfigReader.radiusBlocks(config);
    }
}
