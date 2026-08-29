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

package com.kishku7.chunksmith.api;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.api.event.task.GenerationCompleteEvent;
import com.kishku7.chunksmith.api.event.task.GenerationProgressEvent;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.Parameter;

import java.util.function.Consumer;

@SuppressWarnings("ClassCanBeRecord")
public class ChunksmithAPIImpl implements ChunksmithAPI {
    private final Chunksmith chunky;

    public ChunksmithAPIImpl(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public int version() {
        return 0;
    }

    @Override
    public boolean isRunning(String world) {
        return chunky.getGenerationTasks().containsKey(world);
    }

    @Override
    public boolean startTask(String world, String shape, double centerX, double centerZ, double radiusX, double radiusZ, String pattern) {
        World implWorld = Input.tryWorld(chunky, world).orElse(null);
        if (implWorld == null) {
            return false;
        }
        if (chunky.getGenerationTasks().containsKey(world)) {
            return false;
        }
        Selection selection = Selection.builder(chunky, implWorld)
                .shape(shape).center(centerX, centerZ)
                .radiusX(radiusX).radiusZ(radiusZ)
                .pattern(Parameter.of(pattern)).build();
        GenerationTask task = new GenerationTask(chunky, selection);
        chunky.getGenerationTasks().put(world, task);
        chunky.getScheduler().runTask(task);
        return true;
    }

    @Override
    public boolean pauseTask(String world) {
        GenerationTask task = chunky.getGenerationTasks().get(world);
        if (task == null) {
            return false;
        }
        task.stop(false);
        return true;
    }

    @Override
    public boolean continueTask(String world) {
        World implWorld = Input.tryWorld(chunky, world).orElse(null);
        if (implWorld == null) {
            return false;
        }
        GenerationTask task = chunky.getTaskLoader().loadTask(implWorld).orElse(null);
        if (task == null || task.isCancelled()) {
            return false;
        }
        if (chunky.getGenerationTasks().containsKey(world)) {
            return false;
        }
        chunky.getGenerationTasks().put(world, task);
        chunky.getScheduler().runTask(task);
        return true;
    }

    @Override
    public boolean cancelTask(String world) {
        World implWorld = Input.tryWorld(chunky, world).orElse(null);
        if (implWorld == null) {
            return false;
        }
        if (!chunky.getGenerationTasks().containsKey(world)) {
            return false;
        }
        chunky.getGenerationTasks().remove(world).stop(true);
        chunky.getTaskLoader().cancelTask(implWorld);
        return true;
    }

    @Override
    public void onGenerationProgress(Consumer<GenerationProgressEvent> event) {
        chunky.getEventBus().subscribe(GenerationProgressEvent.class, event);
    }

    @Override
    public void onGenerationComplete(Consumer<GenerationCompleteEvent> event) {
        chunky.getEventBus().subscribe(GenerationCompleteEvent.class, event);
    }
}
