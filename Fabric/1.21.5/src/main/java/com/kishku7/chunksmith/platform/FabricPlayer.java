package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collections;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricPlayer extends FabricSender implements Player {
    private final ServerPlayer player;

    public FabricPlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        // 1.21.5: ServerPlayer.level() still returns Level (the covariant ServerLevel override
        // landed in 1.21.6). A ServerPlayer's level is always a ServerLevel, so the cast is safe.
        return new FabricWorld((net.minecraft.server.level.ServerLevel) player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((FabricWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), Collections.emptySet(), location.getYaw(), location.getPitch(), false);
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
