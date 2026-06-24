package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Map;

public class ProgressCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public ProgressCommand(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        if (generationTasks.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_PROGRESS_NO_TASKS);
            return;
        }
        for (World world : chunky.getServer().getWorlds()) {
            if (generationTasks.containsKey(world.getName())) {
                generationTasks.get(world.getName()).getProgress().sendUpdate(sender);
            }
        }
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
