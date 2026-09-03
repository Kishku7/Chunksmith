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

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

public final class CommandArguments {
    private final int size;
    private final Queue<String> args = new LinkedList<>();

    private CommandArguments(List<String> arguments) {
        final List<String> unquoted = new ArrayList<>(arguments.size());
        for (final String argument : arguments) {
            unquoted.add(unquote(argument));
        }
        this.size = unquoted.size();
        this.args.addAll(unquoted);
    }

    /**
     * Strips one layer of surrounding quotes from a token.
     *
     * <p><b>Why this is needed at all.</b> Every loader builds its arguments by taking the RAW
     * command input and splitting it on spaces, rather than reading the values Brigadier already
     * parsed. So a quoted argument keeps its quotes: they are just characters in the token.
     *
     * <p>That made an IPv6 address impossible to set. {@code /cs set lodBackchannelHost 2001:db8::1}
     * fails in the PARSER -- Brigadier's string() reads an unquoted bare word and stops dead at the
     * colon with "Expected whitespace to end one argument" -- and the quoted form that Minecraft
     * users would reach for next, {@code "2001:db8::1"}, then arrived at the validator WITH the
     * quotes attached and was refused as malformed. Both routes were closed, on a config key whose
     * whole purpose is naming an address, with IPv6 documented as supported.
     *
     * <p>One layer only, and only when the token both starts and ends with the same quote. No
     * command here takes a value that is legitimately quote-wrapped.
     *
     * <p><b>Known limitation, stated rather than hidden:</b> because the split happens on spaces
     * before this runs, a quoted value CONTAINING a space still arrives as several tokens. That is
     * fine for addresses, which cannot contain spaces, and fixing it properly means reading
     * Brigadier's parsed arguments instead of re-splitting the raw line.
     */
    static String unquote(final String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        final char first = value.charAt(0);
        final char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static CommandArguments of(List<String> arguments) {
        return new CommandArguments(arguments);
    }

    public static CommandArguments of(String... arguments) {
        return new CommandArguments(List.of(arguments));
    }

    public static CommandArguments empty() {
        return new CommandArguments(List.of());
    }

    public int size() {
        return size;
    }

    public Optional<String> next() {
        return Optional.ofNullable(args.poll());
    }

    public List<String> remaining() {
        final List<String> arguments = new ArrayList<>(args);
        args.clear();
        return arguments;
    }

    public String joined() {
        return String.join(" ", remaining());
    }
}
