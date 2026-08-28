package com.kishku7.chunksmith.api;

import com.kishku7.chunksmith.api.event.task.GenerationCompleteEvent;
import com.kishku7.chunksmith.api.event.task.GenerationProgressEvent;

import java.util.function.Consumer;

@SuppressWarnings("unused")
public interface ChunksmithAPI {
    int version();

    boolean isRunning(final String world);

    boolean startTask(final String world, final String shape, final double centerX, final double centerZ, final double radiusX, final double radiusZ, final String pattern);

    boolean pauseTask(final String world);

    boolean continueTask(final String world);

    boolean cancelTask(final String world);

    void onGenerationProgress(final Consumer<GenerationProgressEvent> listener);

    void onGenerationComplete(final Consumer<GenerationCompleteEvent> listener);
}
