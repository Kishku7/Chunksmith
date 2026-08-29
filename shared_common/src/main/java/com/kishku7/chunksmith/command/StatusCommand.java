/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
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

package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.lod.net.CsLodControl;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Map;

public class StatusCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public StatusCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        sender.sendMessagePrefixed(TranslationKey.FORMAT_STATUS_VERSION, chunky.getVersion().toString());

        Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        if (generationTasks.isEmpty()) {
            sender.sendMessage(TranslationKey.FORMAT_STATUS_NO_TASKS);
        } else {
            for (World world : chunky.getServer().getWorlds()) {
                GenerationTask task = generationTasks.get(world.getName());
                if (task != null) {
                    task.getProgress().sendUpdate(sender);
                }
            }
        }

        sender.sendMessage(TranslationKey.FORMAT_STATUS_LOD,
                CsLodControl.describe().orElse("not available on this platform"));
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
