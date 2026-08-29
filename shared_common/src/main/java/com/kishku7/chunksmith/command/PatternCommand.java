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
import com.kishku7.chunksmith.iterator.PatternType;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.Parameter;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

import static com.kishku7.chunksmith.util.Translator.translate;

public class PatternCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public PatternCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Optional<String> optionalType = arguments.next().flatMap(Input::tryPattern);
        if (optionalType.isEmpty()) {
            sender.sendMessage(TranslationKey.HELP_PATTERN);
            return;
        }
        String type = optionalType.get();
        Optional<String> value = arguments.next();
        if (PatternType.CSV.equals(type) && value.isEmpty()) {
            sender.sendMessage(TranslationKey.HELP_PATTERN);
            return;
        }
        Parameter pattern = Parameter.of(type, value.orElse(null));
        chunky.getSelection().pattern(pattern);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_PATTERN, translate("pattern_" + pattern.getType()));
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        if (arguments.size() == 1) {
            return PatternType.ALL;
        }
        return List.of();
    }
}
