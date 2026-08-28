package com.kishku7.chunksmith.api;

import com.kishku7.chunksmith.api.event.task.GenerationCompleteEvent;
import com.kishku7.chunksmith.api.event.task.GenerationProgressEvent;

import java.util.function.Consumer;

@SuppressWarnings("unused")
public interface ChunksmithAPI {
    int version();

    boolean isRunning(String world);

    boolean startTask(String world, String shape, double centerX, double centerZ, double radiusX, double radiusZ, String pattern);

    boolean pauseTask(String world);

    boolean continueTask(String world);

    boolean cancelTask(String world);

    void onGenerationProgress(Consumer<GenerationProgressEvent> listener);

    void onGenerationComplete(Consumer<GenerationCompleteEvent> listener);
}
