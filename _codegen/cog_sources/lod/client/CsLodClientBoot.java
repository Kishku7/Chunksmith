/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.client.net.CsLodClientNet;
import com.kishku7.chunksmith.lod.client.ClientPlatform;
import com.kishku7.chunksmith.lod.client.render.DhTarget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the mod proper, the same way on every loader.
 *
 * <p>Each loader's entrypoint does exactly two things: hand the Platform facade whatever the
 * loader gives it (NeoForge's mod event bus; Fabric has nothing to hand over), then call {@link
 * #init()}. Everything the mod DOES lives below this line and is loader-blind.
 */
public final class CsLodClientBoot {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private CsLodClientBoot() {
    }

    public static void init() {
        CsLodClientNet.register();
        ClientPlatform.onClientSetup(CsLodClientBoot::bindRenderers);
        LOGGER.info("Chunksmith: LOD client ready ({})", Renderers.describe());
    }

    /**
     * Bind to Distant Horizons before it can announce a level.
     *
     * <p>DH fires its level-load event during world load, so the listener has to exist before
     * then -- binding it lazily would miss the only announcement we get. {@link DhTarget}
     * hard-references DH types, so it is only class-loaded once DH is known present.
     */
    private static void bindRenderers() {
        if (!Renderers.hasDh()) {
            return;
        }
        try {
            DhTarget.bind();
        } catch (LinkageError error) {
            LOGGER.warn("Distant Horizons present but incompatible, skipping: {}", error.toString());
        }
    }
}
