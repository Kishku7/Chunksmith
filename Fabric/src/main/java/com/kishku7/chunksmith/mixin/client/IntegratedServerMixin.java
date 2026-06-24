package com.kishku7.chunksmith.mixin.client;

import net.minecraft.client.server.IntegratedServer;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin implements MinecraftServerExtension {
    @Inject(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/IntegratedServer;tickPaused()V"))
    private void tickPaused(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        this.chunksmith$runChunkSystemHousekeeping(booleanSupplier);
    }
}
