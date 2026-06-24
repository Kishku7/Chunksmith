package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

public class ConfirmCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public ConfirmCommand(final Chunksmith chunky) {
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
