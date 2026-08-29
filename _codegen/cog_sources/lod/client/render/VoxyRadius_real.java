package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.LodWarnings;
import com.kishku7.chunksmith.lod.client.VoxyConfigReader;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import me.cortex.voxy.client.config.VoxyConfig;

/**
 * Reads the render distance voxy is configured for, upstream's or any fork's.
 *
 * <p>voxy stores this as {@code VoxyConfig.CONFIG.sectionRenderDistance}, in voxy sections: one
 * section is 32 chunks = 512 blocks. Confirmed three ways in voxy 0.2.16-beta itself, not assumed:
 * {@code HierarchicalOcclusionTraverser} uploads {@code pow(sectionRenderDistance * 16 * 32, 2)} as
 * a squared block distance; {@code VoxyUniforms} exposes {@code round(sectionRenderDistance * 32)}
 * as the Iris render-distance uniform, in chunks; and the config slider reads {@code round(srd *
 * 16)} under the tooltip "Render distance of voxy in chunks". {@code
 * VoxyRenderSystem.setRenderDistance} feeds the same field to the RenderDistanceTracker, so this is
 * the radius voxy draws. voxy's default is 16 sections = 8192 blocks, against the 256-block
 * protocol default we used before we could read this: a voxy player was being sent the one region
 * under their feet.
 *
 * <p>The read goes through {@link VoxyConfigReader} rather than touching the field, because the
 * field's type drifts across forks and a compiled field access does not survive it. Upstream
 * declares {@code float sectionRenderDistance}; the srjefers fork (rebased off voxy 0.2.8-alpha,
 * which used an int) ships {@code public int sectionRenderDistance}, and our compiled {@code
 * getfield ...:F} does not resolve against an {@code I} field. The JVM throws {@code
 * NoSuchFieldError}, which the old {@code catch (LinkageError)} here swallowed, dropping the
 * player's radius from 8192 blocks to 256 and reporting that as success. A 32x collapse, proven on
 * the srjefers jar itself rather than reasoned about. The field is now read by name as whatever
 * numeric type it is, and a field we cannot read is announced ({@link LodWarnings}).
 *
 * <p><b>Never call this during mod init.</b> Class-loading {@link VoxyConfig} from our client-init
 * entrypoint leaves voxy permanently inert. It never logs "Initializing voxy instance", never
 * creates its render system, never ingests anything, and never says why. Proved by control run:
 * same fixture, our jar removed, voxy works. Call this only from the join handshake or later.
 * Reflection does not change this: looking a field up still initializes the class that declares it.
 *
 * <p>{@code VoxyConfig.isRenderingEnabled()} is not a usable gate either. It delegates to {@code
 * VoxyCommon.isAvailable()}, which tests a {@code FACTORY} field only assigned at {@code
 * RenderSystem.initRenderer} return, so it reads false on any thread that asks too early and we
 * would silently fall back to the 256-block default with voxy sitting right there.
 */
public final class VoxyRadius {

    private static final String CAUSE_CONFIG = "voxy-config-unreadable";

    private VoxyRadius() {
    }

    public static int blocks() {
        Object config;
        try {
            // Even the holder is fetched by name: a fork that renamed or removed {@code CONFIG} degrades to
            // "no config" instead of throwing a NoSuchFieldError out of our own bytecode.
            config = VoxyConfigReader.staticField(VoxyConfig.class, "CONFIG");
        } catch (RuntimeException | LinkageError e) {
            LodWarnings.once(CAUSE_CONFIG,
                    "voxy is installed but its configuration could not be read (" + e + ")."
                            + " Falling back to a LOD radius of " + CsLodProtocol.DEFAULT_RADIUS_BLOCKS
                            + " blocks instead of the distance voxy is actually set to, so you will see far"
                            + " less distant terrain than voxy can draw. This normally means a voxy fork"
                            + " whose config class differs from upstream. Please report it, with your voxy"
                            + " version.");
            return 0;
        }
        return VoxyConfigReader.radiusBlocks(config);
    }
}
