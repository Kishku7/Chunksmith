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

package com.kishku7.chunksmith.command.suggestion;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import com.kishku7.chunksmith.command.CommandLiteral;
import com.kishku7.chunksmith.command.ConfigSettings;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Completes the setting NAME for {@code /cs set}, straight out of {@link ConfigSettings}.
 *
 * <p>Reading the same list the command reads is the point: nobody has to remember to update completion
 * when a setting is added, and completion can never offer a name the command would then reject.
 */
public class SettingSuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    @Override
    public CompletableFuture<Suggestions> getSuggestions(final CommandContext<CommandSourceStack> context,
                                                         final SuggestionsBuilder builder) {
        try {
            final String input = context.getArgument(CommandLiteral.TYPE, String.class).toLowerCase(Locale.ROOT);
            ConfigSettings.names().forEach(name -> {
                if (name.toLowerCase(Locale.ROOT).contains(input)) {
                    builder.suggest(name);
                }
            });
        } catch (IllegalArgumentException e) {
            ConfigSettings.names().forEach(builder::suggest);
        }
        return builder.buildFuture();
    }
}
