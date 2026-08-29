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

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.shape.ShapeType;

import java.text.DecimalFormat;

public final class Formatting {
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#.##");
    private static final char[] BINARY_PREFIXES = new char[]{'K', 'M', 'G', 'T', 'P'};

    private Formatting() {
    }

    public static String bytes(long bytes) {
        long value = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (value < 1024) {
            return String.format("%d B", bytes);
        }
        int i = BINARY_PREFIXES.length - 1;
        long prefixValue = 1L << (BINARY_PREFIXES.length * 10);
        for (; i > 0; --i) {
            if (value >= prefixValue) {
                break;
            }
            prefixValue >>= 10;
        }
        return String.format("%.1f %cB", bytes / (double) prefixValue, BINARY_PREFIXES[i]);
    }

    public static String radius(Selection selection) {
        if (ShapeType.RECTANGLE.equals(selection.shape()) || ShapeType.ELLIPSE.equals(selection.shape())) {
            return String.format("%s, %s", number(selection.radiusX()), number(selection.radiusZ()));
        } else {
            return String.format("%s", number(selection.radiusX()));
        }
    }

    public static synchronized String number(double number) {
        return NUMBER_FORMAT.format(number);
    }
}
