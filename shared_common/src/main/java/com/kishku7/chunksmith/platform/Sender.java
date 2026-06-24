package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.platform.util.Location;

public interface Sender {
    boolean isPlayer();

    String getName();

    World getWorld();

    Location getLocation();

    boolean hasPermission(String permission);

    void sendMessage(String key, boolean prefixed, Object... args);

    default void sendMessage(String key, Object... args) {
        sendMessage(key, false, args);
    }

    default void sendMessagePrefixed(String key, Object... args) {
        sendMessage(key, true, args);
    }
}
