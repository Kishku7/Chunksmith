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
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

public class CenterCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public CenterCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Optional<Double> newX = arguments.next().flatMap(Input::tryDoubleSuffixed);
        Optional<Double> newZ = arguments.next().flatMap(Input::tryDoubleSuffixed);
        double centerX;
        double centerZ;
        if (newX.isEmpty() && newZ.isEmpty()) {
            Location coordinate = sender.getLocation();
            centerX = coordinate.getX();
            centerZ = coordinate.getZ();
        } else if (newX.isPresent() && newZ.isPresent()) {
            centerX = newX.get();
            centerZ = newZ.get();
        } else {
            sender.sendMessage(TranslationKey.HELP_CENTER);
            return;
        }
        if (Input.isPastWorldLimit(centerX) || Input.isPastWorldLimit(centerZ)) {
            sender.sendMessage(TranslationKey.HELP_CENTER);
            return;
        }
        chunky.getSelection().center(centerX, centerZ);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_CENTER, Formatting.number(centerX), Formatting.number(centerZ));
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
