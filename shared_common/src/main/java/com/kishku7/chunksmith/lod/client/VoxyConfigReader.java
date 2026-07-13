package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.LodWarnings;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.OptionalDouble;

/**
 * Reads a voxy config object -- upstream's OR any fork's -- WITHOUT compiling against its field types.
 *
 * <p><b>This is the one place Chunksmith uses reflection on voxy, and it is deliberate.</b> Everything else
 * we touch in voxy ({@code VoxelIngestService.rawIngest}, {@code VoxyCommon.getInstance()},
 * {@code WorldIdentifier.of}) was verified identical across upstream and all six forks with {@code javap},
 * so it is called directly -- reflection there would cost a lookup per chunk and buy nothing. The CONFIG is
 * different: it is the one place fork drift has actually been OBSERVED.
 *
 * <p><b>The observed drift.</b> Upstream voxy declares {@code public float sectionRenderDistance}. The
 * srjefers fork -- rebased from voxy 0.2.8-alpha, which typed it as an {@code int} -- ships
 * {@code public int sectionRenderDistance}. A field's TYPE is part of its JVM resolution: our compiled
 * {@code getfield ... : F} does not match a field declared {@code I}, so the JVM throws
 * {@code NoSuchFieldError} -- a {@link LinkageError}. We used to catch that and return 0, which meant the
 * server fell back to {@link CsLodProtocol#DEFAULT_RADIUS_BLOCKS} (256 blocks) for a player whose voxy was
 * set to draw 8192. A 32x collapse, in silence.
 *
 * <p>So: look the field up by NAME, ask it what type it actually is, and read it as whatever it is --
 * {@code float}, {@code int}, {@code double}, {@code long}, {@code short}, {@code byte}. The units are the
 * same in every version of voxy (the field counts voxy SECTIONS; a section is 32 chunks = 512 blocks), only
 * the storage type drifted. And if the field is genuinely gone, SAY SO -- see {@link LodWarnings}.
 *
 * <p>Lives in shared_common, not in the voxy seam, for two reasons: it names no voxy type (it takes an
 * {@code Object}), and that makes the whole type-tolerant read unit-testable against fake config objects
 * -- which is exactly how the int/float/absent cases are covered without a Minecraft runtime.
 */
public final class VoxyConfigReader {

    /** Blocks per voxy section: 32 chunks x 16 blocks. Constant in every voxy and every fork. */
    public static final int SECTION_BLOCKS = 512;

    /** The field that carries voxy's render distance, in sections. */
    public static final String RENDER_DISTANCE_FIELD = "sectionRenderDistance";

    /** Warn key -- the config field could not be read at all. */
    private static final String CAUSE_FIELD = "voxy-render-distance-field";

    private VoxyConfigReader() {
    }

    /**
     * voxy's configured render distance in BLOCKS, or 0 when there is nothing to read.
     *
     * <p>Returns 0 -- QUIETLY -- when the config is not there yet, or when the player has simply switched
     * voxy's renderer off. Those are not faults and must not be shouted about.
     *
     * <p>Returns 0 -- LOUDLY, once -- when voxy IS there and configured on, but its render-distance field
     * cannot be found or is not a number. That is fork drift, and the player deserves to know their radius
     * just fell back to {@link CsLodProtocol#DEFAULT_RADIUS_BLOCKS}.
     *
     * @param config the voxy {@code VoxyConfig.CONFIG} instance, or null
     */
    public static int radiusBlocks(final Object config) {
        if (config == null) {
            return 0;
        }
        // A fork that dropped these flags entirely has not "disabled" anything -- absent means "not
        // switched off", so the default is true. Only an explicit false means the player turned it off.
        if (!flag(config, "enabled", true) || !flag(config, "enableRendering", true)) {
            return 0;
        }

        final OptionalDouble sections = number(config, RENDER_DISTANCE_FIELD);
        if (sections.isEmpty()) {
            LodWarnings.once(CAUSE_FIELD,
                    "this voxy (" + config.getClass().getName() + ") has no readable '"
                            + RENDER_DISTANCE_FIELD + "' setting -- it is either missing or not a number."
                            + " That is a voxy fork we do not recognise. Falling back to a LOD radius of "
                            + CsLodProtocol.DEFAULT_RADIUS_BLOCKS + " blocks, which is far less terrain than"
                            + " voxy's own default (8192). Please report this with your voxy version.");
            return 0;
        }

        final double value = sections.getAsDouble();
        if (value <= 0.0) {
            return 0;
        }
        final double blocks = value * SECTION_BLOCKS;
        if (blocks >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(blocks);
    }

    /**
     * Read a numeric field of ANY primitive numeric type, by name.
     *
     * @return its value widened to a double, or empty when the field does not exist, is not a number, or
     *     cannot be read
     */
    public static OptionalDouble number(final Object instance, final String name) {
        final Field field = field(instance, name);
        if (field == null) {
            return OptionalDouble.empty();
        }
        try {
            final Object value = field.get(instance);
            if (value instanceof Number) {
                // Covers float, int, double, long, short, byte -- and their boxed forms, if a fork ever
                // makes the field an Integer/Float. Character and boolean are not Numbers and fall through.
                return OptionalDouble.of(((Number) value).doubleValue());
            }
            return OptionalDouble.empty();
        } catch (final IllegalAccessException | RuntimeException e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Read a boolean field by name.
     *
     * @param fallback what to answer when the field is absent or is not a boolean -- absence is NOT a
     *     "false"; a fork that removed a toggle has not turned the feature off
     */
    public static boolean flag(final Object instance, final String name, final boolean fallback) {
        final Field field = field(instance, name);
        if (field == null) {
            return fallback;
        }
        try {
            final Object value = field.get(instance);
            return value instanceof Boolean ? (Boolean) value : fallback;
        } catch (final IllegalAccessException | RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Read a static field off a class, by name. Null when it is absent, not static, or unreadable.
     *
     * <p>Used for {@code VoxyConfig.CONFIG} itself: even the holder is fetched by name, so a fork that
     * renamed or removed it degrades to "no config" instead of throwing a {@code NoSuchFieldError} out of
     * our own bytecode.
     */
    public static Object staticField(final Class<?> owner, final String name) {
        try {
            final Field field = owner.getField(name);
            if (!Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            return field.get(null);
        } catch (final NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** Find a field by name: public first (voxy's config fields all are), then the declared hierarchy. */
    private static Field field(final Object instance, final String name) {
        if (instance == null) {
            return null;
        }
        try {
            return instance.getClass().getField(name);
        } catch (final NoSuchFieldException | RuntimeException ignored) {
            // Not public (or not visible): fall through to the declared walk.
        }
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field declared = type.getDeclaredField(name);
                declared.setAccessible(true);
                return declared;
            } catch (final NoSuchFieldException ignored) {
                continue;
            } catch (final RuntimeException e) {
                // SecurityException / InaccessibleObjectException -- treat as "cannot read".
                return null;
            }
        }
        return null;
    }
}
