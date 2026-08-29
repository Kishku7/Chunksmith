/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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

package com.kishku7.chunksmith.platform;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import com.kishku7.chunksmith.ChunksmithBukkit;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class BukkitPlayer extends BukkitSender implements Player {
    private static final boolean ACTION_BAR_SUPPORTED;
    private final JavaPlugin plugin = JavaPlugin.getPlugin(ChunksmithBukkit.class);

    static {
        boolean barSupported;
        try {
            org.bukkit.entity.Player.class.getMethod("spigot");
            barSupported = true;
        } catch (NoSuchMethodException e) {
            barSupported = false;
        }
        ACTION_BAR_SUPPORTED = barSupported;
    }

    org.bukkit.entity.Player player;

    public BukkitPlayer(org.bukkit.entity.Player player) {
        super(player);
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName();
    }

    @Override
    public World getWorld() {
        return new BukkitWorld(player.getWorld());
    }

    @Override
    public Location getLocation() {
        org.bukkit.Location location = player.getLocation();
        return new Location(getWorld(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    public UUID getUUID() {
        return player.getUniqueId();
    }

    @Override
    public void teleport(Location location) {
        org.bukkit.World world = Bukkit.getWorld(location.getWorld().getName());
        org.bukkit.Location loc = new org.bukkit.Location(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            teleportAsync(player, loc);
        } else if (Paper.isPaper() && player.getWorld().equals(loc.getWorld())) {
            Paper.teleportAsyncWithPassengers(vehicle, loc);
        } else if (Folia.isFolia() && !Folia.isTickThread(player.getLocation())) {
            Folia.schedule(plugin, player, () -> teleport(location), 1);
        } else {
            List<Entity> passengers = vehicle.getPassengers();
            if (Folia.isFolia()) {
                Folia.schedule(plugin, vehicle, vehicle::eject, 1);
            } else {
                vehicle.eject();
            }
            teleportAsync(player, loc).thenAcceptAsync(ignored -> {
                teleportAsync(vehicle, loc);
                for (Entity passenger : passengers) {
                    teleportAsync(passenger, loc);
                    if (passenger instanceof final org.bukkit.entity.Player playerPassenger) {
                        playerPassenger.hideEntity(plugin, vehicle);
                        playerPassenger.showEntity(plugin, vehicle);
                    }
                    if (Folia.isFolia()) {
                        Folia.schedule(plugin, vehicle, () -> vehicle.addPassenger(passenger), 1);
                    } else {
                        vehicle.addPassenger(passenger);
                    }
                }
            }, command -> {
                if (Folia.isFolia()) {
                    Folia.schedule(plugin, player, command, 1);
                } else {
                    plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, command, 1);
                }
            });
        }
    }

    private CompletableFuture<Boolean> teleportAsync(Entity entity, org.bukkit.Location location) {
        if (Paper.isPaper()) {
            return Paper.teleportAsync(entity, location);
        } else {
            return CompletableFuture.completedFuture(entity.teleport(location));
        }
    }

    @Override
    // Legacy Spigot action-bar fallback (guarded by ACTION_BAR_SUPPORTED): TextComponent.fromLegacyText
    // is deprecated in favour of Adventure, but this pre-Adventure branch must keep the BungeeCord chat
    // API for the older servers it targets.
    @SuppressWarnings("deprecation")
    public void sendActionBar(String key) {
        if (ACTION_BAR_SUPPORTED) {
            String message = formatColored(translateKey(key, false));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
        } else {
            this.sendMessage(key);
        }
    }
}