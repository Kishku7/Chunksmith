package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

public class ConfirmCommand implements ChunkyCommand {
    private final Chunky chunky;

    public ConfirmCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Optional<Runnable> pendingAction = chunky.getPendingAction(sender);
        if (pendingAction.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_CONFIRM);
            return;
        }
        pendingAction.get().run();
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
