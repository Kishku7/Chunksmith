package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.integration.Integration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Server {
    Map<String, Integration> getIntegrations();

    Optional<World> getWorld(String name);

    List<World> getWorlds();

    int getMaxWorldSize();

    Sender getConsole();

    Collection<Player> getPlayers();

    Optional<Player> getPlayer(String name);

    Config getConfig();

    /**
     * Smoothed mean milliseconds-per-tick of the server main thread, used as the
     * primary feedback signal for the adaptive I/O throttle. ~50 ms means a healthy
     * 20 TPS; higher means the server is falling behind. Returns a negative value on
     * platforms that cannot report it, in which case the throttle falls back to its
     * absolute per-chunk latency backstop.
     */
    default double getMillisPerTick() {
        return -1.0D;
    }
}
