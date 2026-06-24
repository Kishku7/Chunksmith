package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PauseCommand implements ChunkyCommand {
    private final Chunky chunky;

    public PauseCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        if (generationTasks.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_PAUSE_NO_TASKS);
            return;
        }
        if (arguments.size() > 0) {
            final Optional<World> world = Input.tryWorld(chunky, arguments.joined());
            if (world.isEmpty() || !generationTasks.containsKey(world.get().getName())) {
                sender.sendMessage(TranslationKey.HELP_PAUSE);
            } else {
                generationTasks.get(world.get().getName()).stop(false);
                sender.sendMessagePrefixed(TranslationKey.FORMAT_PAUSE, world.get().getName());
            }
            return;
        }
        for (GenerationTask generationTask : chunky.getGenerationTasks().values()) {
            generationTask.stop(false);
            sender.sendMessagePrefixed(TranslationKey.FORMAT_PAUSE, generationTask.getSelection().world().getName());
        }
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        if (arguments.size() == 1) {
            final List<String> suggestions = new ArrayList<>();
            chunky.getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        }
        return List.of();
    }
}
