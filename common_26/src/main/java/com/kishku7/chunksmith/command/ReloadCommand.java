package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.event.command.ReloadCommandEvent;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;

public class ReloadCommand implements ChunkyCommand {
    private final Chunky chunky;

    public ReloadCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final String type = arguments.next().orElse(null);
        if ("tasks".equals(type)) {
            if (!chunky.getGenerationTasks().isEmpty()) {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_RELOAD_TASKS_RUNNING);
                return;
            }
            chunky.getTaskLoader().reload();
        } else {
            final Config config = chunky.getServer().getConfig();
            config.reload();
            chunky.setLanguage(config.getLanguage());
            chunky.getEventBus().call(new ReloadCommandEvent());
        }
        sender.sendMessagePrefixed(TranslationKey.FORMAT_RELOAD);
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        if (arguments.size() == 1) {
            return List.of("config", "tasks");
        }
        return List.of();
    }
}
