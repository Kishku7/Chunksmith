/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) pop4959 and contributors.
 *
 * This file is taken from Chunky (https://github.com/pop4959/Chunky)
 * and is unchanged apart from the project rename.
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

package com.kishku7.chunksmith.iterator;

import java.util.List;

public final class PatternType {
    public static final String CONCENTRIC = "concentric";
    public static final String LOOP = "loop";
    public static final String SPIRAL = "spiral";
    public static final String CSV = "csv";
    public static final String REGION = "region";
    public static final String WORLD = "world";

    public static final List<String> ALL = List.of(CONCENTRIC, LOOP, SPIRAL, CSV, REGION, WORLD);

    private PatternType() {
    }
}
