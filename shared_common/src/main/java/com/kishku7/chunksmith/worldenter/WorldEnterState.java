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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * What the world-enter pregen borrowed, so it can be given back.
 *
 * <p>The feature freezes the world, stops time advancing and raises the
 * throttle. All three have to be undone afterwards, and "afterwards" includes
 * the case where there is no afterwards -- the player alt-F4s during the freeze,
 * or the JVM dies. Restoring on the normal path is easy and is not what this
 * class is for.
 *
 * <p>So the borrowed values are written to disk <b>before</b> anything is
 * changed, and deleted only once everything has been handed back. A file left
 * behind on startup therefore means exactly one thing: we changed those settings
 * and never restored them. That is the signal to restore and move on.
 *
 * <p><b>Why the original values and not the vanilla defaults.</b> Somebody may
 * have deliberately turned time advancement off, or set an unusual tick budget.
 * Resetting to "what Minecraft ships with" would silently overwrite a choice
 * they made, and they would have no way of knowing we did it. The only correct
 * restore is the value that was there before we touched it.
 *
 * <p>Per world, not global: two saves can be mid-pregen independently, and the
 * state belongs to the world whose settings were changed. It lives beside the
 * world data rather than in the user's config, because it is bookkeeping rather
 * than a setting -- putting it in {@code config.json} would also mean inventing
 * config keys that have no {@code /cs set}, which the house rule forbids.
 */
public final class WorldEnterState {

    /** Written inside the world directory: {@code <world>/chunksmith/worldenter.json}. */
    public static final String FILE_NAME = "worldenter.json";

    private final boolean timeAdvanceWasOn;
    private final long dayTime;
    private final long tickBudgetMillis;
    private final long playerReserveMillis;
    private final double targetMspt;

    public WorldEnterState(boolean timeAdvanceWasOn, long dayTime, long tickBudgetMillis,
                           long playerReserveMillis, double targetMspt) {
        this.timeAdvanceWasOn = timeAdvanceWasOn;
        this.dayTime = dayTime;
        this.tickBudgetMillis = tickBudgetMillis;
        this.playerReserveMillis = playerReserveMillis;
        this.targetMspt = targetMspt;
    }

    public boolean timeAdvanceWasOn() {
        return timeAdvanceWasOn;
    }

    public long dayTime() {
        return dayTime;
    }

    public long tickBudgetMillis() {
        return tickBudgetMillis;
    }

    public long playerReserveMillis() {
        return playerReserveMillis;
    }

    public double targetMspt() {
        return targetMspt;
    }

    /**
     * Writes the state, then flushes it into place.
     *
     * <p>Written to a temporary file and moved, because a half-written state
     * file is worse than none: the restore would read a truncated value and hand
     * back something the player never had. A move is atomic where the filesystem
     * supports it, so the file is either the previous state or the new one.
     *
     * @return false if it could not be written, in which case the CALLER MUST NOT
     *         proceed to change anything -- there would be no way back
     */
    public boolean write(Path worldDir) {
        Path dir = worldDir.resolve("chunksmith");
        Path target = dir.resolve(FILE_NAME);
        Path temp = dir.resolve(FILE_NAME + ".tmp");
        String json = "{\n"
                + "  \"timeAdvanceWasOn\": " + timeAdvanceWasOn + ",\n"
                + "  \"dayTime\": " + dayTime + ",\n"
                + "  \"tickBudgetMillis\": " + tickBudgetMillis + ",\n"
                + "  \"playerReserveMillis\": " + playerReserveMillis + ",\n"
                + "  \"targetMspt\": " + targetMspt + "\n"
                + "}\n";
        try {
            Files.createDirectories(dir);
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // Some filesystems refuse ATOMIC_MOVE. A plain replace is still better than
                // writing in place, and the window it leaves open is one rename wide.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads a state left behind by a previous session, if there is one.
     *
     * <p>A file that cannot be parsed is treated as absent rather than as an
     * error. It means the same thing in practice -- we cannot tell what to
     * restore -- and refusing to load the world over it would punish the player
     * for our bookkeeping.
     */
    public static Optional<WorldEnterState> read(Path worldDir) {
        Path target = worldDir.resolve("chunksmith").resolve(FILE_NAME);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            String json = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            return Optional.of(new WorldEnterState(
                    Boolean.parseBoolean(field(json, "timeAdvanceWasOn", "true")),
                    Long.parseLong(field(json, "dayTime", "0")),
                    Long.parseLong(field(json, "tickBudgetMillis", "25")),
                    Long.parseLong(field(json, "playerReserveMillis", "20")),
                    Double.parseDouble(field(json, "targetMspt", "150.0"))));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Removes the state. Called once everything really has been handed back. */
    public static void clear(Path worldDir) {
        try {
            Files.deleteIfExists(worldDir.resolve("chunksmith").resolve(FILE_NAME));
        } catch (IOException ignored) {
            // A state file we failed to delete costs one redundant restore next load, which is
            // harmless -- it writes back values that are already in force.
        }
    }

    /**
     * Pulls one value out without a JSON parser.
     *
     * <p>shared_common is compiled into a plugin jar and three loader jars, and this
     * file is written by us, read by us, and never by anything else. A five-field
     * flat object does not justify dragging a dependency across that boundary.
     */
    private static String field(String json, String name, String fallback) {
        String key = "\"" + name + "\"";
        int at = json.indexOf(key);
        if (at < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) {
            return fallback;
        }
        int end = colon + 1;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                && json.charAt(end) != '\n') {
            end++;
        }
        String value = json.substring(colon + 1, end).trim();
        return value.isEmpty() ? fallback : value;
    }
}
