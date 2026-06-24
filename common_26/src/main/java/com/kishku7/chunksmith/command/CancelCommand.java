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

public class CancelCommand implements ChunkyCommand {
    private final Chunky chunky;

    public CancelCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        final Map<String, TrimCommand.Task> trimTasks = chunky.getTrimTasks();
        if (generationTasks.isEmpty()
                && chunky.getTaskLoader().loadTasks().stream().allMatch(GenerationTask::isCancelled)
                && trimTasks.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL_NO_TASKS);
            return;
        }
        final Runnable cancelAction;
        if (arguments.size() > 0) {
            final Optional<World> world = Input.tryWorld(chunky, arguments.joined());
            if (world.isEmpty()) {
                sender.sendMessage(TranslationKey.HELP_CANCEL);
                return;
            }
            cancelAction = () -> {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL, world.get().getName());
                chunky.getTaskLoader().cancelTask(world.get());
                if (chunky.getGenerationTasks().containsKey(world.get().getName())) {
                    chunky.getGenerationTasks().remove(world.get().getName()).stop(true);
                }
                if (chunky.getTrimTasks().containsKey(world.get().getName())) {
                    chunky.getTrimTasks().remove(world.get().getName()).setCancelled(true);
                }
            };
        } else {
            cancelAction = () -> {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL_ALL);
                chunky.getTaskLoader().cancelTasks();
                chunky.getGenerationTasks().values().forEach(generationTask -> generationTask.stop(true));
                chunky.getGenerationTasks().clear();
                chunky.getScheduler().cancelTasks();
                chunky.getTrimTasks().values().forEach(trimTask -> trimTask.setCancelled(true));
                chunky.getTrimTasks().clear();
            };
        }
        chunky.setPendingAction(sender, cancelAction);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_CANCEL_CONFIRM, "/cs confirm");
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
