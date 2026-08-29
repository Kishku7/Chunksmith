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

package com.kishku7.chunksmith.util;

import java.util.Optional;

public class Parameter {
    private final String type;
    private final String value;

    public Parameter(String type, String value) {
        this.type = type;
        this.value = value;
    }

    public Parameter(String expression) {
        final String[] parts = expression.split("=");
        this.type = parts[0];
        this.value = parts.length > 1 ? parts[1] : null;
    }

    public static Parameter of(String expression) {
        return new Parameter(expression);
    }

    public static Parameter of(String type, String value) {
        return new Parameter(type, value);
    }

    public String getType() {
        return type;
    }

    public Optional<String> getValue() {
        return Optional.ofNullable(value);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder(type);
        if (value != null) {
            builder.append("=").append(value);
        }
        return builder.toString();
    }
}
