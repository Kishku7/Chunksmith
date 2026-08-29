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
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

public class RadiusCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public RadiusCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        final Optional<String> newX = arguments.next();
        final Optional<Integer> signX = newX.flatMap(Input::trySign);
        final Optional<Double> newRadiusX = newX.map(x -> signX.isPresent() ? x.substring(1) : x).flatMap(Input::tryDoubleSuffixed);
        if (newRadiusX.isEmpty() || newRadiusX.get() < 0 || Input.isPastWorldLimit(newRadiusX.get())) {
            sender.sendMessage(TranslationKey.HELP_RADIUS);
            return;
        }
        final Selection current = chunky.getSelection().build();
        final double radiusX = signX.map(sign -> current.radiusX() + sign * newRadiusX.get()).orElseGet(newRadiusX::get);
        if (radiusX < 0 || Input.isPastWorldLimit(radiusX)) {
            sender.sendMessage(TranslationKey.HELP_RADIUS);
            return;
        }
        final Optional<String> newZ = arguments.next();
        if (newZ.isPresent()) {
            final Optional<Integer> signZ = newZ.flatMap(Input::trySign);
            final Optional<Double> newRadiusZ = newZ.map(z -> signZ.isPresent() ? z.substring(1) : z).flatMap(Input::tryDoubleSuffixed);
            if (newRadiusZ.isEmpty() || newRadiusZ.get() < 0 || Input.isPastWorldLimit(newRadiusZ.get())) {
                sender.sendMessage(TranslationKey.HELP_RADIUS);
                return;
            }
            final double radiusZ = signZ.map(sign -> current.radiusZ() + sign * newRadiusZ.get()).orElseGet(newRadiusZ::get);
            if (radiusZ < 0 || Input.isPastWorldLimit(radiusZ)) {
                sender.sendMessage(TranslationKey.HELP_RADIUS);
                return;
            }
            chunky.getSelection().radiusX(radiusX).radiusZ(radiusZ);
            sender.sendMessagePrefixed(TranslationKey.FORMAT_RADII, Formatting.number(radiusX), Formatting.number(radiusZ));
        } else {
            chunky.getSelection().radius(radiusX);
            sender.sendMessagePrefixed(TranslationKey.FORMAT_RADIUS, Formatting.number(radiusX));
        }
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
