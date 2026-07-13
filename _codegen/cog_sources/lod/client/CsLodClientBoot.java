package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.client.net.CsLodClientNet;
import com.kishku7.chunksmith.lod.client.ClientPlatform;
import com.kishku7.chunksmith.lod.client.render.DhTarget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's actual startup -- shared by every loader.
 *
 * <p>Each loader's entrypoint does exactly two things: hand the Platform facade whatever the loader gives
 * it (NeoForge's mod event bus; Fabric has nothing to hand over), then call {@link #init()}. Everything the
 * mod DOES lives below this line and is loader-blind.
 */
public final class CsLodClientBoot {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private CsLodClientBoot() {
    }

    public static void init() {
        CsLodClientNet.register();
        ClientPlatform.onClientSetup(CsLodClientBoot::bindRenderers);
        LOGGER.info("Chunksmith: LOD client ready -- {}", Renderers.describe());
    }

    /**
     * Bind to Distant Horizons before it can announce a level.
     *
     * <p>DH fires its level-load event DURING world load, so the listener has to exist before then --
     * binding it lazily would miss the only announcement we get. {@link DhTarget} hard-references DH types,
     * so it is only class-loaded once DH is known present.
     */
    private static void bindRenderers() {
        if (!Renderers.hasDh()) {
            return;
        }
        try {
            DhTarget.bind();
        } catch (final LinkageError error) {
            LOGGER.warn("Distant Horizons present but incompatible, skipping: {}", error.toString());
        }
    }
}
