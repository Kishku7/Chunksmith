package com.kishku7.chunksmith.platform;

import net.minecraft.server.level.ServerLevel;

/**
 * Implemented by each loader's World wrapper to expose the underlying Minecraft ServerLevel,
 * so shared Minecraft-touching code can reach it without depending on a loader-specific
 * World class (FabricWorld / NeoForgeWorld).
 */
public interface ServerLevelHolder {
    ServerLevel getWorld();
}