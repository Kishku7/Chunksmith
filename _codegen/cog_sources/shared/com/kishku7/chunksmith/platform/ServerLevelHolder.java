package com.kishku7.chunksmith.platform;

import net.minecraft.server.level.ServerLevel;

public interface ServerLevelHolder {
    ServerLevel getWorld();
}