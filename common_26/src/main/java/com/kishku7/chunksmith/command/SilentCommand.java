package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;

import static com.kishku7.chunksmith.util.Translator.translate;

public class SilentCommand implements ChunkyCommand {
    private final Chunky chunky;

    public SilentCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        chunky.getConfig().setSilent(!chunky.getConfig().isSilent());
        final String status = translate(chunky.getConfig().isSilent() ? TranslationKey.ENABLED : TranslationKey.DISABLED);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_SILENT, status);
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
