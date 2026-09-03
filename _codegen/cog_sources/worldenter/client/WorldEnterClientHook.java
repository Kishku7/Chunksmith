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
        Screen current = currentScreen(client);
        boolean active = WorldEnterPregen.isActive();

        if (active) {
            // Only when vanilla has left the screen empty -- see the class javadoc.
            if (current == null) {
                showScreen(client, new WorldEnterScreen());
            }
            return;
        }
        // Generation finished (or something released the world) while the screen was still up.
        if (current instanceof WorldEnterScreen) {
            showScreen(client, null);
        }
    }

    // The screen accessor MOVED IN 26.2, so all three call sites are generated rather than written.
    //
    //   26.1.2 -> Minecraft.screen (a public field) + Minecraft.setScreen(Screen)
    //   26.2+  -> Minecraft.gui.screen()            + Minecraft.gui.setScreen(Screen)
    //
    // Verified with javap against both jars: on 26.1.2 `Gui` carries neither method. This was found
    // by BUILDING 26.1.2 after the screen had been written against 26.2 only -- the 26.2 form
    // compiles clean on 26.2 and fails on 26.1 with "cannot find symbol: method screen()". "26.x" is
    // not one API, and an era check would not have caught it because every 26.x shares one era.

    //[[[cog
    // import cog, compat
    // if compat.screen_holder(mcver) == "gui":
    //     cog.outl("private static Screen currentScreen(Minecraft client) {")
    //     cog.outl("    return client.gui.screen();")
    //     cog.outl("}")
    //     cog.outl("")
    //     cog.outl("private static void showScreen(Minecraft client, Screen screen) {")
    //     cog.outl("    client.gui.setScreen(screen);")
    //     cog.outl("}")
    // else:
    //     cog.outl("private static Screen currentScreen(Minecraft client) {")
    //     cog.outl("    return client.screen;")
    //     cog.outl("}")
    //     cog.outl("")
    //     cog.outl("private static void showScreen(Minecraft client, Screen screen) {")
    //     cog.outl("    client.setScreen(screen);")
    //     cog.outl("}")
    //]]]
    //[[[end]]]
}
