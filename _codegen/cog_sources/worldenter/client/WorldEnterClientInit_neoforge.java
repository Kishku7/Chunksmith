package com.kishku7.chunksmith.worldenter.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * NeoForge's client-side half of the world-enter pregen.
 *
 * <p>The side guard is {@code value = Dist.CLIENT} on the subscriber. FML reads that off the
 * annotation SCAN, without loading the class, so on a dedicated server this class -- and the screen
 * it reaches -- is never loaded at all. That is the property that matters: a runtime {@code if}
 * would still class-load a Screen subclass on a server.
 *
 * <p>A game-bus {@code @EventBusSubscriber} rather than a second {@code @Mod(dist = Dist.CLIENT)}
 * class beside {@code LodClientInit}: {@code ClientTickEvent.Post} is a game-bus event, and this
 * needs no constructor injection, so the subscriber is the smaller, more honest declaration of what
 * it actually is. Note that FML 10 (NeoForge 26) has no {@code bus} element on the annotation at
 * all -- the mod bus moved to constructor injection -- so game-bus is not a choice here, it is the
 * only bus the annotation addresses.
 */
@EventBusSubscriber(modid = "chunksmith", value = Dist.CLIENT)
public final class WorldEnterClientInit {

    private WorldEnterClientInit() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        WorldEnterClientHook.tick(Minecraft.getInstance());
    }
}
