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

    public ChunksmithAPIImpl(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public int version() {
        return 0;
    }

    @Override
    public boolean isRunning(final String world) {
        return chunky.getGenerationTasks().containsKey(world);
    }

    @Override
    public boolean startTask(final String world, final String shape, final double centerX, final double centerZ, final double radiusX, final double radiusZ, final String pattern) {
        final World implWorld = Input.tryWorld(chunky, world).orElse(null);
        if (implWorld == null) {
            return false;
        }
        if (chunky.getGenerationTasks().containsKey(world)) {
            return false;
        }
        final Selection selection = Selection.builder(chunky, implWorld)
                .shape(shape).center(centerX, centerZ)
                .radiusX(radiusX).radiusZ(radiusZ)
                .pattern(Parameter.of(pattern)).build();
        final GenerationTask task = new GenerationTask(chunky, selection);
        chunky.getGenerationTasks().put(world, task);
        chunky.getScheduler().runTask(task);
        return true;
    }

    @Override
    public boolean pauseTask(final String world) {
        final GenerationTask task = chunky.getGenerationTasks().get(world);
        if (task == null) {
            return false;
        }
        task.stop(false);
        return true;
    }

    @Override
    public boolean continueTask(final String world) {
        final World implWorld = Input.tryWorld(chunky, world).orElse(null);
        if (implWorld == null) {
            return false;
        }
        final GenerationTask task = chunky.getTaskLoader().loadTask(implWorld).orElse(null);
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
    public boolean cancelTask(final String world) {
        final World implWorld = Input.tryWorld(chunky, world).orElse(null);
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
    public void onGenerationProgress(final Consumer<GenerationProgressEvent> event) {
        chunky.getEventBus().subscribe(GenerationProgressEvent.class, event);
    }

    @Override
    public void onGenerationComplete(final Consumer<GenerationCompleteEvent> event) {
        chunky.getEventBus().subscribe(GenerationCompleteEvent.class, event);
    }
}
