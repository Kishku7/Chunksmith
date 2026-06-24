package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;

public class SpawnCommand implements ChunkyCommand {
    private final Chunky chunky;

    public SpawnCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        chunky.getSelection().spawn();
        final Selection current = chunky.getSelection().build();
        sender.sendMessagePrefixed(TranslationKey.FORMAT_CENTER, Formatting.number(current.centerX()), Formatting.number(current.centerZ()));
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
