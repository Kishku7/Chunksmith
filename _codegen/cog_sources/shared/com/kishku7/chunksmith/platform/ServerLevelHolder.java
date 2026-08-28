package com.kishku7.chunksmith.platform;

import net.minecraft.server.level.ServerLevel;

/**
 * Implemented by each loader's World wrapper (FabricWorld / NeoForgeWorld) so shared code can
 * reach the ServerLevel without depending on a loader-specific class.
 */
public interface ServerLevelHolder {
    ServerLevel getWorld();
}