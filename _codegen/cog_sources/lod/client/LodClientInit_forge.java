package com.kishku7.chunksmith.lod.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The LOD CLIENT entrypoint -- classic FORGE (MC 1.20.1 / Forge 47).
 *
 * <p><b>This class is the side guard, and it is the LOADER that enforces it.</b> Forge 47's {@code @Mod} has
 * no {@code dist} parameter -- that arrived with NeoForge -- so the guard cannot live on the main
 * entrypoint. It does not have to: {@code @Mod.EventBusSubscriber} takes a {@code Dist[] value()}, and FML
 * filters subscribers by the running distribution BEFORE class-loading them. On a dedicated server this
 * class is never loaded, and neither is anything it reaches ({@code ClientPlatform}, the download client,
 * the renderer adapters, {@code net.minecraft.client.*}). No runtime {@code if}, no reliance on lazy
 * constant-pool resolution, no {@code DistExecutor}.
 *
 * <p>It rides the MOD bus because {@code FMLClientSetupEvent} is a mod-bus event -- which also means this
 * class IS the "client setup" moment, so {@code ClientPlatform.onClientSetup} on this loader simply runs the
 * action. (That event is deliberately late: Forge constructs mods in dependency order and Distant Horizons
 * is a soft dependency we declare no load order against, so our constructor can run before DH's and
 * {@code DhApi.events} would not exist yet. {@code FMLClientSetupEvent} runs after every mod is constructed
 * and still long before DH announces a level.)
 *
 * <p>The channel is NOT built here. {@code CsLodChannel}'s static initializer builds it during mod
 * construction on BOTH sides -- the only window in which Forge's network registry accepts a new
 * SimpleChannel -- and that is the mod's ONE registration of {@code chunksmith:lod}. This class installs the
 * client SINK on it.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod/client. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
@Mod.EventBusSubscriber(modid = "chunksmith", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LodClientInit {

    private LodClientInit() {
    }

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(CsLodClientBoot::init);
    }
}
