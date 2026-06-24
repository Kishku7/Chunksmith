package com.kishku7.chunksmith.command.suggestion;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import com.kishku7.chunksmith.command.CommandLiteral;
import com.kishku7.chunksmith.shape.ShapeType;

import java.util.concurrent.CompletableFuture;

public class ShapeSuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    @Override
    public CompletableFuture<Suggestions> getSuggestions(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        try {
            final String input = context.getArgument(CommandLiteral.SHAPE, String.class);
            ShapeType.all().forEach(shape -> {
                if (shape.contains(input.toLowerCase())) {
                    builder.suggest(shape);
                }
            });
        } catch (IllegalArgumentException e) {
            ShapeType.all().forEach(builder::suggest);
        }
        return builder.buildFuture();
    }
}
