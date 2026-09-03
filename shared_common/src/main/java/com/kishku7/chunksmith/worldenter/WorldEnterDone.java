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
 * A world that has already been pre-generated, so it is never pre-generated again.
 *
 * <p><b>Once it is done, it is done.</b> Without this record the world-enter pregen fires on EVERY
 * single-player load: the task skips the chunks that already exist, so it is not destructive, but
 * the player is put behind the progress screen again on a world they already waited for. That is
 * the whole reason this class exists.
 *
 * <p>Deliberately NOT the same file as {@link WorldEnterState}, and the difference is the point.
 * {@code WorldEnterState} is TRANSIENT bookkeeping -- it exists only while settings are borrowed,
 * and its presence at startup means "we crashed, restore". This record is PERMANENT: it survives
 * exactly as long as the world does. Merging them would make "we are mid-pregen" and "we finished
 * a pregen" the same signal, and the crash-restore path would then delete the completion.
 *
 * <p><b>Only a real completion writes it.</b> A player who presses "Enter World Now" leaves a
 * partial run and no record, so the next load CONTINUES where it left off -- which is the wanted
 * behaviour and is why cancelling must not count as finishing.
 *
 * <p><b>The radius is stored, not just a flag.</b> Someone who raises
 * {@code worldEnterPregenRadius} is asking for more world than they have, and a bare boolean would
 * silently refuse them forever. Storing what was actually completed lets a raised radius re-arm the
 * feature, and a lowered one still counts as satisfied.
 */
public final class WorldEnterDone {

    /** Written inside the world directory: {@code <world>/chunksmith/worldenter-done.json}. */
    public static final String FILE_NAME = "worldenter-done.json";

    private final String dimension;
    private final long radiusBlocks;
    private final long completedAtMillis;

    public WorldEnterDone(String dimension, long radiusBlocks, long completedAtMillis) {
        this.dimension = dimension;
        this.radiusBlocks = radiusBlocks;
        this.completedAtMillis = completedAtMillis;
    }

    public String dimension() {
        return dimension;
    }

    public long radiusBlocks() {
        return radiusBlocks;
    }

    public long completedAtMillis() {
        return completedAtMillis;
    }

    /**
     * Does this record already satisfy a request for {@code wantedRadius} in {@code wantedDimension}?
     *
     * <p>Greater-or-equal, not equal: a completed 8192 covers a configured 4096. Lowering the
     * setting is not a reason to re-run something already finished.
     */
    public boolean satisfies(String wantedDimension, long wantedRadius) {
        return dimension != null
                && dimension.equals(wantedDimension)
                && radiusBlocks >= wantedRadius;
    }

    /**
     * Writes the record, temp-then-move, for the same reason {@link WorldEnterState#write} does:
     * a truncated record would parse as a smaller radius and re-run a finished world.
     *
     * @return false if it could not be written. The caller should carry on regardless -- failing to
     *         record a completion costs one redundant pregen next load, whereas refusing to finish
     *         would cost the player the run they just waited for.
     */
    public boolean write(Path worldDir) {
        Path dir = worldDir.resolve("chunksmith");
        Path target = dir.resolve(FILE_NAME);
        Path temp = dir.resolve(FILE_NAME + ".tmp");
        String json = "{\n"
                + "  \"dimension\": \"" + dimension + "\",\n"
                + "  \"radiusBlocks\": " + radiusBlocks + ",\n"
                + "  \"completedAtMillis\": " + completedAtMillis + "\n"
                + "}\n";
        try {
            Files.createDirectories(dir);
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads the completion record, if the world has one.
     *
     * <p>An unparseable record is treated as absent. That errs towards running the pregen again
     * rather than towards refusing to ever run it, which is the safer of the two mistakes: the
     * redundant run is visible and skippable, whereas a feature that silently never fires again
     * looks like a bug with no evidence.
     */
    public static Optional<WorldEnterDone> read(Path worldDir) {
        Path target = worldDir.resolve("chunksmith").resolve(FILE_NAME);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            String json = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            String dim = field(json, "dimension", "");
            long radius = Long.parseLong(field(json, "radiusBlocks", "0"));
            if (dim.isEmpty() || radius <= 0L) {
                return Optional.empty();
            }
            return Optional.of(new WorldEnterDone(dim, radius,
                    Long.parseLong(field(json, "completedAtMillis", "0"))));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Removes the record, so the world is eligible again. Used by {@code /cs} tooling and tests. */
    public static void clear(Path worldDir) {
        try {
            Files.deleteIfExists(worldDir.resolve("chunksmith").resolve(FILE_NAME));
        } catch (IOException ignored) {
            // Nothing useful to do: the world simply stays marked as done.
        }
    }

    /**
     * Pulls one value out without a JSON parser, unquoting strings.
     *
     * <p>Same reasoning as {@link WorldEnterState}: shared_common is compiled into a plugin jar and
     * three loader jars, and a three-field flat object we both write and read does not justify a
     * dependency across that boundary.
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
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? fallback : value;
    }
}
