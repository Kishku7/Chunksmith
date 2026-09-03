package com.kishku7.chunksmith.worldenter.client;

import com.kishku7.chunksmith.worldenter.WorldEnterPregen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Decides, once per client tick, whether the world-enter screen should be on screen.
 *
 * <p>No packet is involved, and none is needed: world-enter pregen only ever runs on an INTEGRATED
 * server, which lives in this same JVM, so the client can simply read
 * {@link WorldEnterPregen#isActive()} directly. That is why the feature is single-player-only rather
 * than single-player-first -- the cheap direct read is the whole design, and a multiplayer version
 * would be a different feature with a protocol.
 *
 * <p>The open condition is deliberately narrow: only when NO screen is showing. During world load
 * vanilla owns the screen (the receiving-level screen), and stomping it would break the load. Once
 * vanilla is finished it leaves the screen null, and that is our cue.
 */
public final class WorldEnterClientHook {

    private WorldEnterClientHook() {
    }

    public static void tick(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }
        Screen current = client.gui.screen();
        boolean active = WorldEnterPregen.isActive();

        if (active) {
            // Only when vanilla has left the screen empty -- see the class javadoc.
            if (current == null) {
                client.gui.setScreen(new WorldEnterScreen());
            }
            return;
        }
        // Generation finished (or something released the world) while the screen was still up.
        if (current instanceof WorldEnterScreen) {
            client.gui.setScreen(null);
        }
    }
}
