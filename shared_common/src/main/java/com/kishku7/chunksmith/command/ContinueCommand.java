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

    public ContinueCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
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
            } else if (generationTasks.get(world.getName()).isStopping()) {
                // A pause/stop drains before it lets go of the map entry, and that takes seconds. Saying
                // "already started" here is the exact opposite of what is true -- the run is on its way
                // DOWN, and answering that way left operators with a stopped pregen and a message telling
                // them it was fine. Say what is actually happening and what to do about it.
                sender.sendMessagePrefixed(TranslationKey.FORMAT_TASK_STOPPING, world.getName());
            } else {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_STARTED_ALREADY, world.getName());
            }
        });
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        if (arguments.size() == 1) {
            final List<String> suggestions = new ArrayList<>();
            chunky.getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        }
        return List.of();
    }
}
