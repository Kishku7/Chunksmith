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

package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.LodWarnings;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.OptionalDouble;

/**
 * Reads a voxy config object (upstream's or any fork's) without compiling against its field types.
 *
 * <p><b>The one place Chunksmith uses reflection on voxy, and it is deliberate.</b> Everything
 * else we touch ({@code VoxelIngestService.rawIngest}, {@code VoxyCommon.getInstance()},
 * {@code WorldIdentifier.of}) was verified identical across upstream and all six forks with
 * {@code javap}, so it is called directly. The config is the one place fork drift has actually
 * been observed.
 *
 * <p><b>The observed drift.</b> Upstream voxy declares {@code public float
 * sectionRenderDistance}. The srjefers fork (rebased from voxy 0.2.8-alpha, which typed it as
 * an {@code int}) ships {@code public int sectionRenderDistance}. A field's type is part of
 * its JVM resolution: our compiled {@code getfield ... : F} does not match a field declared
 * {@code I}, so the JVM throws {@code NoSuchFieldError}, a {@link LinkageError}. We used to
 * catch that and return 0, so the server fell back to {@link
 * CsLodProtocol#DEFAULT_RADIUS_BLOCKS} (256 blocks) for a player whose voxy was set to draw
 * 8192. A 32x collapse, in silence.
 *
 * <p>So: look the field up by name, ask it what type it actually is, and read it as whatever
 * it is. The units are the same in every version of voxy (the field counts voxy sections; a
 * section is 32 chunks = 512 blocks), only the storage type drifted. If the field is genuinely
 * gone, say so; see {@link LodWarnings}. It names no voxy type (it takes an {@code Object}),
 * which is what makes the int/float/absent cases testable without a Minecraft runtime.
 */
public final class VoxyConfigReader {

    /** Blocks per voxy section: 32 chunks x 16 blocks. Constant in every voxy and every fork. */
    public static final int SECTION_BLOCKS = 512;

    /** The field that carries voxy's render distance, in sections. */
    public static final String RENDER_DISTANCE_FIELD = "sectionRenderDistance";

    /** Warn key: the config field could not be read at all. */
    private static final String CAUSE_FIELD = "voxy-render-distance-field";

    private VoxyConfigReader() {
    }

    /**
     * Returns voxy's configured render distance in blocks, or 0 when there is nothing to read.
     *
     * <p>0 quietly when the config is not there yet or the player has switched voxy's renderer
     * off, and those are not faults. 0 loudly, once, when voxy is there and configured on but
     * its render-distance field cannot be found or is not a number, which is fork drift, and
     * the player deserves to know their radius just fell back to {@link
     * CsLodProtocol#DEFAULT_RADIUS_BLOCKS}.
     *
     * @param config the voxy {@code VoxyConfig.CONFIG} instance, or null
     * @return the radius in blocks, or 0 when there is nothing to read
     */
    public static int radiusBlocks(Object config) {
        if (config == null) {
            return 0;
        }
        // A fork that dropped these flags entirely has not "disabled" anything. Absent means "not
        // switched off", so the default is true. Only an explicit false means the player turned it off.
        if (!flag(config, "enabled", true) || !flag(config, "enableRendering", true)) {
            return 0;
        }

        OptionalDouble sections = number(config, RENDER_DISTANCE_FIELD);
        if (sections.isEmpty()) {
            LodWarnings.once(CAUSE_FIELD,
                    "this voxy (" + config.getClass().getName() + ") has no readable '"
                            + RENDER_DISTANCE_FIELD + "' setting; it is either missing or not a number."
                            + " That is a voxy fork we do not recognise. Falling back to a LOD radius of "
                            + CsLodProtocol.DEFAULT_RADIUS_BLOCKS + " blocks, which is far less terrain than"
                            + " voxy's own default (8192). Please report this with your voxy version.");
            return 0;
        }

        double value = sections.getAsDouble();
        if (value <= 0.0) {
            return 0;
        }
        double blocks = value * SECTION_BLOCKS;
        if (blocks >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(blocks);
    }

    /**
     * Reads a numeric field of any primitive numeric type, by name.
     *
     * @return its value widened to a double, or empty when it does not exist or is not a number
     */
    public static OptionalDouble number(Object instance, String name) {
        Field field = field(instance, name);
        if (field == null) {
            return OptionalDouble.empty();
        }
        try {
            Object value = field.get(instance);
            if (value instanceof Number) {
                // Covers float, int, double, long, short, byte and their boxed forms. Character and
                // boolean are not Numbers and fall through.
                return OptionalDouble.of(((Number) value).doubleValue());
            }
            return OptionalDouble.empty();
        } catch (IllegalAccessException | RuntimeException e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Reads a boolean field by name.
     *
     * @param fallback what to answer when the field is absent or is not a boolean; absence is not a
     *     "false", since a fork that removed a toggle has not turned the feature off
     * @return the field's value, or {@code fallback}
     */
    public static boolean flag(Object instance, String name, boolean fallback) {
        Field field = field(instance, name);
        if (field == null) {
            return fallback;
        }
        try {
            Object value = field.get(instance);
            return value instanceof Boolean ? (Boolean) value : fallback;
        } catch (IllegalAccessException | RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Reads a static field off a class, by name. Null when it is absent, not static, or
     * unreadable. Used for {@code VoxyConfig.CONFIG} itself, where even the holder is fetched
     * by name, so a fork that renamed it degrades to "no config" instead of throwing {@code
     * NoSuchFieldError} out of our bytecode.
     */
    public static Object staticField(Class<?> owner, String name) {
        try {
            Field field = owner.getField(name);
            if (!Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            return field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** Finds a field by name. Public first (voxy's config fields all are), then the declared hierarchy. */
    private static Field field(Object instance, String name) {
        if (instance == null) {
            return null;
        }
        try {
            return instance.getClass().getField(name);
        } catch (NoSuchFieldException | RuntimeException ignored) {
            // Not public (or not visible): fall through to the declared walk.
        }
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field declared = type.getDeclaredField(name);
                declared.setAccessible(true);
                return declared;
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (RuntimeException e) {
                // SecurityException / InaccessibleObjectException: treat as "cannot read".
                return null;
            }
        }
        return null;
    }
}
