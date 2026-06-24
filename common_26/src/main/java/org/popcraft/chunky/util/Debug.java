package org.popcraft.chunky.util;

/**
 * Runtime toggle for Chunksmith's on-demand diagnostic logging, flipped by the {@code /cs debug}
 * command. Default OFF: the instrumentation in the mixins is a no-op and emits nothing, so a normal
 * install is silent. When a server owner is unsure whether the worldgen entity-unload fix is keeping
 * up, {@code /cs debug} turns on a once-per-5s per-dimension entity-manager stats line in the log.
 */
public final class Debug {
    public static volatile boolean ENABLED = false;

    private Debug() {
    }
}