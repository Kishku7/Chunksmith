package com.kishku7.chunksmith.lod.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The LOD CLIENT entrypoint -- NEOFORGE.
 *
 * <p>The main {@code @Mod} class ({@code ChunksmithNeoForge}) carries no {@code dist} and runs everywhere,
 * as it must -- it owns the ONE registration of the {@code chunksmith:lod} payload type, via
 * {@code CsLodChannel.registerPayloads(modBus)}. This class registers no payload; it installs the client
 * SINK that the clientbound handler drains into.
 */
@Mod(value = "chunksmith", dist = Dist.CLIENT)
public class LodClientInit {

    public LodClientInit(final ModContainer mod, final IEventBus bus, final Dist dist) {
        ClientPlatform.bootstrap(bus);
        CsLodClientBoot.init();
    }
}
