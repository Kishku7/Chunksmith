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

public class CancelCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public CancelCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        Map<String, TrimCommand.Task> trimTasks = chunky.getTrimTasks();
        if (generationTasks.isEmpty()
                && chunky.getTaskLoader().loadTasks().stream().allMatch(GenerationTask::isCancelled)
                && trimTasks.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL_NO_TASKS);
            return;
        }
        Runnable cancelAction;
        if (arguments.size() > 0) {
            Optional<World> world = Input.tryWorld(chunky, arguments.joined());
            if (world.isEmpty()) {
                sender.sendMessage(TranslationKey.HELP_CANCEL);
                return;
            }
            cancelAction = () -> {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL, world.get().getName());
                chunky.getTaskLoader().cancelTask(world.get());
                if (chunky.getGenerationTasks().containsKey(world.get().getName())) {
                    chunky.getGenerationTasks().remove(world.get().getName()).stop(true);
                }
                if (chunky.getTrimTasks().containsKey(world.get().getName())) {
                    chunky.getTrimTasks().remove(world.get().getName()).setCancelled(true);
                }
            };
        } else {
            cancelAction = () -> {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL_ALL);
                chunky.getTaskLoader().cancelTasks();
                chunky.getGenerationTasks().values().forEach(generationTask -> generationTask.stop(true));
                chunky.getGenerationTasks().clear();
                chunky.getScheduler().cancelTasks();
                chunky.getTrimTasks().values().forEach(trimTask -> trimTask.setCancelled(true));
                chunky.getTrimTasks().clear();
            };
        }
        chunky.setPendingAction(sender, cancelAction);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL_CONFIRM, "/cs confirm");
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
