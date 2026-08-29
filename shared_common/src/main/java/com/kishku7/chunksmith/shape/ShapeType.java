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

package com.kishku7.chunksmith.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ShapeType {
    public static final String CIRCLE = "circle";
    public static final String DIAMOND = "diamond";
    public static final String ELLIPSE = "ellipse";
    public static final String HEXAGON = "hexagon";
    public static final String OVAL = "oval";
    public static final String PENTAGON = "pentagon";
    public static final String RECTANGLE = "rectangle";
    public static final String SQUARE = "square";
    public static final String STAR = "star";
    public static final String TRIANGLE = "triangle";

    private static final List<String> DEFAULTS = List.of(CIRCLE, DIAMOND, ELLIPSE, HEXAGON, PENTAGON, RECTANGLE, SQUARE, STAR, TRIANGLE);

    private ShapeType() {
    }

    public static List<String> all() {
        Set<String> customTypes = ShapeFactory.getCustomTypes();
        if (customTypes.isEmpty()) {
            return DEFAULTS;
        }
        List<String> allTypes = new ArrayList<>(DEFAULTS);
        allTypes.addAll(customTypes);
        return allTypes;
    }
}
