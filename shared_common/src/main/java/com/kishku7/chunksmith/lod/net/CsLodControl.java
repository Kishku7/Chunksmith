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

package com.kishku7.chunksmith.lod.net;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class CsLodControl {

    @FunctionalInterface
    public interface Action {
        int rebind();
    }

    private static volatile Action action;
    private static volatile IntSupplier gamePort;
    private static volatile Supplier<String> describe;

    private CsLodControl() {
    }

    public static void register(final Action rebindAction,
                                final IntSupplier gamePortSupplier,
                                final Supplier<String> describeSupplier) {
        action = rebindAction;
        gamePort = gamePortSupplier;
        describe = describeSupplier;
    }

    public static void clear() {
        action = null;
        gamePort = null;
        describe = null;
    }

    public static OptionalInt apply() {
        Action current = action;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.rebind());
    }

    public static OptionalInt gamePort() {
        IntSupplier current = gamePort;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.getAsInt());
    }

    public static Optional<String> describe() {
        Supplier<String> current = describe;
        return current == null ? Optional.empty() : Optional.ofNullable(current.get());
    }
}
