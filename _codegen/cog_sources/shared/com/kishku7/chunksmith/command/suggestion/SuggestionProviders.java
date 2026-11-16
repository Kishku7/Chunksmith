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

package com.kishku7.chunksmith.command.suggestion;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;

public final class SuggestionProviders {
    public static final SuggestionProvider<CommandSourceStack> PATTERNS;
    public static final SuggestionProvider<CommandSourceStack> SHAPES;
    public static final SuggestionProvider<CommandSourceStack> TRIM_MODES;
    public static final SuggestionProvider<CommandSourceStack> SETTINGS;

    static {
        PATTERNS = wrap(new PatternSuggestionProvider());
        SHAPES = wrap(new ShapeSuggestionProvider());
        TRIM_MODES = wrap(new TrimModeSuggestionProvider());
        SETTINGS = wrap(new SettingSuggestionProvider());
    }

    // 26.4 only accepts suggestion providers its command tree can serialise: anything else makes
    // Commands.<init> throw "Invalid suggestion type". These providers read only the named
    // arguments, never the source, so handing them the context the server supplies is safe.
    //[[[cog
    // import cog, compat
    // if compat.command_args_wrapped(mcver):
    //     cog.outl("    @SuppressWarnings(\"unchecked\")")
    //     cog.outl("    private static SuggestionProvider<CommandSourceStack> wrap(SuggestionProvider<CommandSourceStack> provider) {")
    //     cog.outl("        return net.minecraft.commands.suggestions.SuggestionProviders.serverOnly(")
    //     cog.outl("                (server, context, builder) -> provider.getSuggestions(")
    //     cog.outl("                        (com.mojang.brigadier.context.CommandContext<CommandSourceStack>) (com.mojang.brigadier.context.CommandContext<?>) context, builder));")
    //     cog.outl("    }")
    // else:
    //     cog.outl("    private static SuggestionProvider<CommandSourceStack> wrap(SuggestionProvider<CommandSourceStack> provider) {")
    //     cog.outl("        return provider;")
    //     cog.outl("    }")
    //]]]
    private static SuggestionProvider<CommandSourceStack> wrap(SuggestionProvider<CommandSourceStack> provider) {
        return provider;
    }
    //[[[end]]]

    private SuggestionProviders() {
    }
}
