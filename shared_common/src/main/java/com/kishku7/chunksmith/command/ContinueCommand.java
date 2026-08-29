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

public class ContinueCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public ContinueCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        List<GenerationTask> loadTasks;
        if (arguments.size() > 0) {
            Optional<World> world = Input.tryWorld(chunky, arguments.joined());
            if (world.isEmpty()) {
                sender.sendMessage(TranslationKey.HELP_CONTINUE);
                return;
            }
            loadTasks = chunky.getTaskLoader().loadTask(world.get()).map(List::of).orElse(List.of());
        } else {
            loadTasks = chunky.getTaskLoader().loadTasks();
        }
        if (loadTasks.stream().allMatch(GenerationTask::isCancelled)) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_CONTINUE_NO_TASKS);
            return;
        }
        Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        loadTasks.stream().filter(task -> !task.isCancelled()).forEach(generationTask -> {
            World world = generationTask.getSelection().world();
            if (!generationTasks.containsKey(world.getName())) {
                generationTasks.put(world.getName(), generationTask);
                chunky.getScheduler().runTask(generationTask);
                sender.sendMessagePrefixed(TranslationKey.FORMAT_CONTINUE, world.getName());
            } else if (generationTasks.get(world.getName()).isStopping()) {
                // A pause/stop drains before it lets go of the map entry, and that takes seconds. Saying
                // "already started" here is the exact opposite of what is true: the run is on its way
                // DOWN, and answering that way left operators with a stopped pregen and a message telling
                // them it was fine. Say what is actually happening and what to do about it.
                sender.sendMessagePrefixed(TranslationKey.FORMAT_TASK_STOPPING, world.getName());
            } else {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_STARTED_ALREADY, world.getName());
            }
        });
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
