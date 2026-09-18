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
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ProgressCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public ProgressCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        if (generationTasks.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_PROGRESS_NO_TASKS);
            return;
        }
        // Same correction as StatusCommand, and for the same reason: this filtered the task map
        // through getServer().getWorlds() and printed nothing whatsoever when no world matched,
        // so the one command a player is told to run when a pregen looks stuck could answer with
        // silence. Read the map, which is what actually decides whether a task exists.
        generationTasks.values().stream()
                .sorted(Comparator.comparing(task -> task.getProgress().getWorld()))
                .forEach(task -> task.getProgress().sendUpdate(sender));
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
