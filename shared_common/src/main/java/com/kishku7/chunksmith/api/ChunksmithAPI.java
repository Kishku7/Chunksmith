package com.kishku7.chunksmith.api;

import com.kishku7.chunksmith.api.event.task.GenerationCompleteEvent;
import com.kishku7.chunksmith.api.event.task.GenerationProgressEvent;

import java.util.function.Consumer;

/** The Chunksmith API. */
@SuppressWarnings("unused")
public interface ChunksmithAPI {
    /** The API version this build implements. */
    int version();

    /** Whether a generation task is currently running in the given world. */
    boolean isRunning(final String world);

    /**
     * Starts a generation task with a given selection in a world.
     *
     * @param world   The world identifier
     * @param shape   The selection shape
     * @param centerX The center x coordinate
     * @param centerZ The center z coordinate
     * @param radiusX The primary radius (x-axis)
     * @param radiusZ The secondary radius (z-axis) (only used for certain shapes)
     * @param pattern The generation pattern
     * @return If the task was created and started successfully
     */
    boolean startTask(final String world, final String shape, final double centerX, final double centerZ, final double radiusX, final double radiusZ, final String pattern);

    /** Pauses the generation task in the given world; returns whether it was paused. */
    boolean pauseTask(final String world);

    /** Continues the generation task in the given world; returns whether it was continued. */
    boolean continueTask(final String world);

    /** Cancels the generation task in the given world; returns whether it was cancelled. */
    boolean cancelTask(final String world);

    /** Registers a listener for generation progress events. */
    void onGenerationProgress(final Consumer<GenerationProgressEvent> listener);

    /** Registers a listener for generation complete events. */
    void onGenerationComplete(final Consumer<GenerationCompleteEvent> listener);
}
