package com.kishku7.chunksmith.listeners.bossbar;

//[[[cog
// import cog, compat
// cog.outl(compat.identifier_import(mcver))
//]]]
//[[[end]]]
import net.minecraft.server.level.ServerBossEvent;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.event.task.GenerationTaskFinishEvent;
import com.kishku7.chunksmith.platform.World;

import java.util.Map;
import java.util.function.Consumer;

/**
 * COG DRIFT: the MC resource-id class was renamed ResourceLocation -> Identifier at 26. Cog emits
 * the correct type + import so one source compiles on every runtime.
 */
public class BossBarTaskFinishListener implements Consumer<GenerationTaskFinishEvent> {
    //[[[cog
    // import cog, compat
    // t = compat.identifier_type(mcver)
    // cog.outl("private final Map<%s, ServerBossEvent> bossBars;" % t)
    //]]]
    //[[[end]]]

    //[[[cog
    // import cog, compat
    // t = compat.identifier_type(mcver)
    // cog.outl("public BossBarTaskFinishListener(final Map<%s, ServerBossEvent> bossBars) {" % t)
    //]]]
    //[[[end]]]
        this.bossBars = bossBars;
    }

    @Override
    public void accept(final GenerationTaskFinishEvent event) {
        final GenerationTask task = event.generationTask();
        final World world = task.getSelection().world();
        //[[[cog
        // import cog, compat
        // t = compat.identifier_type(mcver)
        // cog.outl("final %s worldIdentifier = %s.tryParse(world.getKey());" % (t, t))
        //]]]
        //[[[end]]]
        if (worldIdentifier == null) {
            return;
        }
        final ServerBossEvent bossBar = bossBars.get(worldIdentifier);
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBars.remove(worldIdentifier);
        }
    }
}
