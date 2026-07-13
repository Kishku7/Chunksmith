package com.kishku7.chunksmith.lod.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The LOD CLIENT entrypoint -- NEOFORGE.
 *
 * <p><b>This class is the side guard.</b> A SECOND {@code @Mod} class for the same mod id, declared
 * {@code dist = Dist.CLIENT}. NeoForge's mod loader only CONSTRUCTS a {@code @Mod} class whose {@code dist}
 * matches the running distribution -- on a dedicated server this class is never instantiated and never
 * class-loaded, and neither is anything it reaches ({@code ClientPlatform}, the download client, the
 * renderer adapters, {@code net.minecraft.client.*}). It is the loader's own mechanism, not a runtime
 * {@code if}, which is why it is used in preference to one.
 *
 * <p>The main {@code @Mod} class ({@code ChunksmithNeoForge}) carries no {@code dist} and runs everywhere,
 * as it must -- it owns the ONE registration of the {@code chunksmith:lod} payload type, via
 * {@code CsLodChannel.registerPayloads(modBus)}. This class registers no payload; it installs the client
 * SINK that the clientbound handler drains into.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod/client. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
@Mod(value = "chunksmith", dist = Dist.CLIENT)
public class LodClientInit {

    public LodClientInit(final ModContainer mod, final IEventBus bus, final Dist dist) {
        ClientPlatform.bootstrap(bus);
        CsLodClientBoot.init();
    }
}
