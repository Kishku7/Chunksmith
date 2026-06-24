package com.kishku7.chunksmith.event.task;

import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.event.Event;

public record GenerationTaskFinishEvent(GenerationTask generationTask) implements Event {
}
