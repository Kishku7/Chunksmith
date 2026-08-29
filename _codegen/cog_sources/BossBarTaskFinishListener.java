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
 * One source, every runtime: the MC resource-id class was renamed ResourceLocation ->
 * Identifier at 26, and Cog emits the correct type and import here.
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
    public void accept(GenerationTaskFinishEvent event) {
        GenerationTask task = event.generationTask();
        World world = task.getSelection().world();
        //[[[cog
        // import cog, compat
        // t = compat.identifier_type(mcver)
        // cog.outl("final %s worldIdentifier = %s.tryParse(world.getKey());" % (t, t))
        //]]]
        //[[[end]]]
        if (worldIdentifier == null) {
            return;
        }
        ServerBossEvent bossBar = bossBars.get(worldIdentifier);
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBars.remove(worldIdentifier);
        }
    }
}
