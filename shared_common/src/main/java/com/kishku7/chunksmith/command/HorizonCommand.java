/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
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
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

/**
 * {@code /cs horizon <radius|off>} -- the LOD horizon of the selection (mod_support #39). Chunks past it
 * are generated only for their LOD and never saved, which is what keeps a huge LOD pregen off the disk.
 */
public class HorizonCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public HorizonCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        final Optional<String> value = arguments.next();
        if (value.isEmpty()) {
            // No argument: say what it is set to, which is the question anyone typing the bare command has.
            final double current = chunky.getSelection().build().horizon();
            if (current > 0) {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_HORIZON, Formatting.number(current));
            } else {
                sender.sendMessagePrefixed(TranslationKey.FORMAT_HORIZON_OFF);
            }
            sender.sendMessage(TranslationKey.HELP_HORIZON);
            return;
        }
        final String text = value.get();
        if ("off".equalsIgnoreCase(text) || "0".equals(text)) {
            chunky.getSelection().horizon(0d);
            sender.sendMessagePrefixed(TranslationKey.FORMAT_HORIZON_OFF);
            return;
        }
        final Optional<Double> horizon = Input.tryDoubleSuffixed(text);
        if (horizon.isEmpty() || horizon.get() <= 0 || Input.isPastWorldLimit(horizon.get())) {
            sender.sendMessage(TranslationKey.HELP_HORIZON);
            return;
        }
        chunky.getSelection().horizon(horizon.get());
        sender.sendMessagePrefixed(TranslationKey.FORMAT_HORIZON, Formatting.number(horizon.get()));
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return arguments.size() == 1 ? List.of("off") : List.of();
    }
}
