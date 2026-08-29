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

package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kishku7.chunksmith.util.AutoPause;

public class PauseCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public PauseCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        // A human pause outranks auto-pause in both directions: it must not be undone by the resume
        // watcher, and it clears any outstanding auto-pause so a later recovery does not restart a
        // run the operator deliberately stopped.
        AutoPause.clear();
        Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        if (generationTasks.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_PAUSE_NO_TASKS);
            return;
        }
        if (arguments.size() > 0) {
            Optional<World> world = Input.tryWorld(chunky, arguments.joined());
            if (world.isEmpty() || !generationTasks.containsKey(world.get().getName())) {
                sender.sendMessage(TranslationKey.HELP_PAUSE);
            } else {
                generationTasks.get(world.get().getName()).stop(false);
                sender.sendMessagePrefixed(TranslationKey.FORMAT_PAUSE, world.get().getName());
            }
            return;
        }
        for (GenerationTask generationTask : chunky.getGenerationTasks().values()) {
            generationTask.stop(false);
            sender.sendMessagePrefixed(TranslationKey.FORMAT_PAUSE, generationTask.getSelection().world().getName());
        }
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        if (arguments.size() == 1) {
            List<String> suggestions = new ArrayList<>();
            chunky.getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        }
        return List.of();
    }
}
