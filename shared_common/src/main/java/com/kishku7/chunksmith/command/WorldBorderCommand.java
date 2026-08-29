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
import com.kishku7.chunksmith.integration.BorderIntegration;
import com.kishku7.chunksmith.integration.Integration;
import com.kishku7.chunksmith.platform.Border;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.platform.util.Vector2;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WorldBorderCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public WorldBorderCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        if (arguments.size() > 0) {
            final Optional<World> world = arguments.next().flatMap(arg -> Input.tryWorld(chunky, arg));
            if (world.isPresent()) {
                chunky.getSelection().world(world.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_WORLDBORDER);
                return;
            }
        }
        final Selection previous = chunky.getSelection().build();
        if (!setBorderViaIntegration(previous.world())) {
            chunky.getSelection().worldborder();
        }
        final Selection current = chunky.getSelection().build();
        sender.sendMessagePrefixed(TranslationKey.FORMAT_CENTER, Formatting.number(current.centerX()), Formatting.number(current.centerZ()));
        if (current.radiusX() == current.radiusZ()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_RADIUS, Formatting.number(current.radiusX()));
        } else {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_RADII, Formatting.number(current.radiusX()), Formatting.number(current.radiusZ()));
        }
        if (!previous.shape().equals(current.shape())) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_SHAPE, current.shape());
        }
        if (current.radiusX() > chunky.getServer().getMaxWorldSize()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_WORLDBORDER_TOO_LARGE, chunky.getServer().getMaxWorldSize());
        }
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        if (arguments.size() == 1) {
            final List<String> suggestions = new ArrayList<>();
            chunky.getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        }
        return List.of();
    }

    boolean setBorderViaIntegration(World world) {
        final Map<String, Integration> integrations = chunky.getServer().getIntegrations();
        if (integrations.containsKey("border")) {
            final BorderIntegration worldborder = (BorderIntegration) integrations.get("border");
            final String worldName = world.getName();
            if (worldborder.hasBorder(worldName)) {
                final Border border = worldborder.getBorder(worldName);
                final Vector2 center = border.getCenter();
                chunky.getSelection().center(center.getX(), center.getZ())
                        .radiusX(border.getRadiusX()).radiusZ(border.getRadiusZ())
                        .shape(border.getShape());
                return true;
            }
        }
        return false;
    }
}
