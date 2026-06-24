package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;

import static com.kishku7.chunksmith.util.Translator.translate;

public class HelpCommand implements ChunksmithCommand {
    private final Chunksmith chunky;
    private final List<String> helpCommands = List.of(
            CommandLiteral.START,
            CommandLiteral.PAUSE,
            CommandLiteral.CONTINUE,
            CommandLiteral.CANCEL,
            CommandLiteral.WORLD,
            CommandLiteral.WORLDBORDER,
            CommandLiteral.CENTER,
            CommandLiteral.SPAWN,
            CommandLiteral.RADIUS,
            CommandLiteral.CORNERS,
            CommandLiteral.SHAPE,
            CommandLiteral.PATTERN,
            CommandLiteral.SILENT,
            CommandLiteral.QUIET,
            CommandLiteral.TRIM,
            CommandLiteral.SELECTION,
            CommandLiteral.PROGRESS,
            CommandLiteral.BORDER,
            CommandLiteral.RELOAD
    );

    public HelpCommand(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final List<String> visibleCommands = new ArrayList<>();
        for (final String command : helpCommands) {
            if (chunky.getCommands().containsKey(command)) {
                visibleCommands.add(command);
            }
        }
        final int visibleCommandCount = visibleCommands.size();
        final StringBuilder help = new StringBuilder();
        final int pageIndexLast = visibleCommandCount / 8;
        final int pageIndex = (arguments.size() < 1 ? 0 : Math.max(0, arguments.next().flatMap(Input::tryInteger).orElse(1) - 1)) % (pageIndexLast + 1);
        final int helpIndexFirst;
        final int helpIndexLast;
        if (sender.isPlayer()) {
            helpIndexFirst = 8 * pageIndex;
            helpIndexLast = Math.min(helpIndexFirst + 8, visibleCommandCount);
        } else {
            helpIndexFirst = 0;
            helpIndexLast = visibleCommandCount;
        }
        for (int i = helpIndexFirst; i < helpIndexLast; ++i) {
            help.append('\n').append(translate("help_" + visibleCommands.get(i)));
        }
        if (sender.isPlayer() && pageIndex != pageIndexLast) {
            help.append('\n').append(translate(TranslationKey.HELP_MORE, "/cs help " + (pageIndex + 2)));
        }
        sender.sendMessage(TranslationKey.HELP_MENU, help.toString());
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
