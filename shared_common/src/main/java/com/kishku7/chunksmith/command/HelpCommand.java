/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
            CommandLiteral.SET,
            CommandLiteral.TRIM,
            CommandLiteral.SELECTION,
            CommandLiteral.PROGRESS,
            CommandLiteral.BORDER,
            CommandLiteral.RELOAD
    );

    public HelpCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        List<String> visibleCommands = new ArrayList<>();
        for (String command : helpCommands) {
            if (chunky.getCommands().containsKey(command)) {
                visibleCommands.add(command);
            }
        }
        int visibleCommandCount = visibleCommands.size();
        StringBuilder help = new StringBuilder();
        int pageIndexLast = visibleCommandCount / 8;
        int pageIndex = (arguments.size() < 1 ? 0 : Math.max(0, arguments.next().flatMap(Input::tryInteger).orElse(1) - 1)) % (pageIndexLast + 1);
        int helpIndexFirst;
        int helpIndexLast;
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
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
