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
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;

import static com.kishku7.chunksmith.util.Translator.translate;

public class SelectionCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public SelectionCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Selection current = chunky.getSelection().build();
        sender.sendMessagePrefixed(TranslationKey.FORMAT_SELECTION);
        sender.sendMessage(TranslationKey.FORMAT_SELECTION_WORLD, current.world().getName());
        sender.sendMessage(TranslationKey.FORMAT_SELECTION_SHAPE, translate("shape_" + current.shape()));
        sender.sendMessage(TranslationKey.FORMAT_SELECTION_CENTER, Formatting.number(current.centerX()), Formatting.number(current.centerZ()));
        double radiusX = current.radiusX();
        double radiusZ = current.radiusZ();
        if (radiusX == radiusZ) {
            sender.sendMessage(TranslationKey.FORMAT_SELECTION_RADIUS, Formatting.number(radiusX));
        } else {
            sender.sendMessage(TranslationKey.FORMAT_SELECTION_RADII, Formatting.number(radiusX), Formatting.number(radiusZ));
        }
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
