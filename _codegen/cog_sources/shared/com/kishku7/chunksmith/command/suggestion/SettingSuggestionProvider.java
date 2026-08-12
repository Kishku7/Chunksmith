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
