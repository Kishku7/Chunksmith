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

package com.kishku7.chunksmith.worldenter;

import java.util.List;
import java.util.Locale;

/**
 * The shape of the world-enter pregen (mod_support #51, {@code worldEnterPregenShape}).
 *
 * <p>The pregen engine already draws every one of these; world-enter just never asked for anything but
 * a circle. Only shapes that mean something with a single radius are offered: an ellipse is a circle and a
 * rectangle a square until you give them two different radii, which this setting does not.
 *
 * <p>The radius means the same thing for every shape: the distance from the centre to the farthest point.
 * A circle of radius r reaches r in every direction; a square of radius r reaches r to each side, so it is
 * larger, and it reaches r * sqrt(2) into the corners.
 */
public final class WorldEnterShape {

    public static final String DEFAULT = "circle";

    /** Offered shapes, in the order they are listed in errors. */
    public static final List<String> ALL =
            List.of("circle", "square", "diamond", "hexagon", "pentagon", "triangle", "star");

    private WorldEnterShape() {
    }

    /** The canonical name for what somebody typed, or null when it is not an offered shape. */
    public static String parse(String raw) {
        if (raw == null) {
            return null;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        return ALL.contains(wanted) ? wanted : null;
    }

    /**
     * Roughly how many chunks the shape holds at this radius. An ESTIMATE, used only for the progress bar's
     * first moments and the log line: the bar follows the task's own percentage as soon as one arrives.
     * Regular polygons use their circumradius; the star is a five-point star and is the loosest guess.
     */
    public static long estimateChunks(String shape, long radiusBlocks) {
        double r = radiusBlocks / 16.0;
        double area;
        switch (shape == null ? DEFAULT : shape) {
            case "square" -> area = 4.0 * r * r;
            case "diamond" -> area = 2.0 * r * r;
            case "hexagon" -> area = 1.5 * Math.sqrt(3.0) * r * r;
            case "pentagon" -> area = 2.5 * Math.sin(2.0 * Math.PI / 5.0) * r * r;
            case "triangle" -> area = 0.75 * Math.sqrt(3.0) * r * r;
            case "star" -> area = 1.12 * r * r;
            default -> area = Math.PI * r * r;
        }
        return (long) Math.ceil(area);
    }

    /**
     * Does a finished run of {@code done} cover a wanted run of {@code wanted} at the same centre and
     * radius? A shape always covers itself, and a square covers any other shape: every shape here fits
     * inside the square of the same radius. Nothing else is claimed. A finished circle does NOT cover a
     * square, which is the whole reason the shape is recorded.
     */
    public static boolean covers(String done, String wanted) {
        String d = done == null ? DEFAULT : done;
        String w = wanted == null ? DEFAULT : wanted;
        return d.equals(w) || "square".equals(d);
    }
}
