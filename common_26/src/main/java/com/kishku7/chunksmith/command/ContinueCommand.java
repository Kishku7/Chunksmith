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

public class ContinueCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public ContinueCommand(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final List<GenerationTask> loadTasks;
        if (arguments.size() > 0) {
            final Optional<World> world = Input.tryWorld(chunky, arguments.joined());
            if (world.isEmpty()) {
                sender.sendMessage(TranslationKey.HELP_CONTINUE);
                return;
            }
            loadTasks = chunky.getTaskLoader().loadTask(world.get()).map(List::of).orElse(List.of());
        } else {
            loadTasks = chunky.getTaskLoader().loadTasks();
        }
        if (loadTasks.stream().allMatch(GenerationTask::isCancelled)) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_CONTINUE_NO_TASKS);
            return;
        }
        final Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        loadTasks.stream().filter(task -> !task.isCancelled()).forEach(generationTask -> {
            final World world = generationTask.getSelection().world();
            if (!generationTasks.containsKey(world.getName())) {
                generationTasks.put(world.getName(), generationTask);
                chunky.getScheduler().runTask(generationTask);
                sender.sendMessagePrefixed(TranslationKey.FORMAT_CONTINUE, world.getName());
            } else {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_STARTED_ALREADY, world.getName());
            }
        });
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
