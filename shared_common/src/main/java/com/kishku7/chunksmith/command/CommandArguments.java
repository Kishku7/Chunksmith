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
        this.size = arguments.size();
        this.args.addAll(arguments);
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
