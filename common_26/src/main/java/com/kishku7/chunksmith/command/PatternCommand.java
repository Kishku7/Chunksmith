package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.iterator.PatternType;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.Parameter;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

import static com.kishku7.chunksmith.util.Translator.translate;

public class PatternCommand implements ChunkyCommand {
    private final Chunky chunky;

    public PatternCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Optional<String> optionalType = arguments.next().flatMap(Input::tryPattern);
        if (optionalType.isEmpty()) {
            sender.sendMessage(TranslationKey.HELP_PATTERN);
            return;
        }
        final String type = optionalType.get();
        final Optional<String> value = arguments.next();
        if (PatternType.CSV.equals(type) && value.isEmpty()) {
            sender.sendMessage(TranslationKey.HELP_PATTERN);
            return;
        }
        final Parameter pattern = Parameter.of(type, value.orElse(null));
        chunky.getSelection().pattern(pattern);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_PATTERN, translate("pattern_" + pattern.getType()));
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        if (arguments.size() == 1) {
            return PatternType.ALL;
        }
        return List.of();
    }
}
