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

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.util.Translator;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

public final class ShapeFactory {
    private static final Map<String, BiFunction<Selection, Boolean, Shape>> custom = new HashMap<>();

    private ShapeFactory() {
    }

    public static Shape getShape(Selection selection) {
        return getShape(selection, true);
    }

    public static Shape getShape(Selection selection, boolean chunkAligned) {
        return switch (selection.shape()) {
            case ShapeType.CIRCLE -> new Circle(selection, chunkAligned);
            case ShapeType.DIAMOND -> new Diamond(selection, chunkAligned);
            case ShapeType.ELLIPSE, ShapeType.OVAL -> new Ellipse(selection, chunkAligned);
            case ShapeType.HEXAGON -> new Hexagon(selection, chunkAligned);
            case ShapeType.PENTAGON -> new Pentagon(selection, chunkAligned);
            case ShapeType.RECTANGLE -> new Rectangle(selection, chunkAligned);
            case ShapeType.STAR -> new Star(selection, chunkAligned);
            case ShapeType.TRIANGLE -> new Triangle(selection, chunkAligned);
            default -> custom.getOrDefault(selection.shape(), Square::new).apply(selection, chunkAligned);
        };
    }

    public static void registerCustom(String name, BiFunction<Selection, Boolean, Shape> shapeFunction) {
        custom.put(name, shapeFunction);
        Translator.addCustomTranslation("shape_%s".formatted(name), name);
    }

    public static Set<String> getCustomTypes() {
        return custom.keySet();
    }
}
