package com.kishku7.chunksmith.lod.client;

import com.kishku7.chunksmith.lod.LodWarnings;
import org.junit.Before;
import org.junit.Test;

import java.util.OptionalDouble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The type-tolerant read of voxy's render-distance setting.
 *
 * <p>These fakes ARE the fork matrix: upstream voxy declares {@code float sectionRenderDistance}, the
 * srjefers fork (rebased from 0.2.8-alpha) declares {@code int}, and a hypothetical future fork could
 * declare {@code double}, {@code long}, or drop the field altogether. Every one of those must produce the
 * right radius -- or, when it truly cannot be read, a 0 AND a warning, never a silent 0.
 *
 * <p>The old code compiled {@code cfg.sectionRenderDistance} as a getfield with descriptor {@code F}, so
 * the {@code int} fork threw {@code NoSuchFieldError} at runtime and the radius silently collapsed 8192 ->
 * 256. That regression is what {@link #intFieldReadsTheSameRadiusAsFloat()} pins down.
 */
public class VoxyConfigReaderTest {

    /** Upstream voxy: float, in sections. Default 16 sections = 8192 blocks. */
    public static final class UpstreamConfig {
        public boolean enabled = true;
        public boolean enableRendering = true;
        public float sectionRenderDistance = 16.0f;
    }

    /** srjefers-style fork: the SAME field, the SAME units, declared int. */
    public static final class IntConfig {
        public boolean enabled = true;
        public boolean enableRendering = true;
        public int sectionRenderDistance = 16;
    }

    /** A fork that widened it. */
    public static final class DoubleConfig {
        public boolean enabled = true;
        public boolean enableRendering = true;
        public double sectionRenderDistance = 16.0;
    }

    /** A fork that used a long. */
    public static final class LongConfig {
        public boolean enabled = true;
        public boolean enableRendering = true;
        public long sectionRenderDistance = 16L;
    }

    /** A fork that renamed or removed the field entirely -- the loud case. */
    public static final class NoFieldConfig {
        public boolean enabled = true;
        public boolean enableRendering = true;
    }

    /** A fork that kept the field but made it something that is not a number. */
    public static final class NotANumberConfig {
        public boolean enabled = true;
        public boolean enableRendering = true;
        public String sectionRenderDistance = "far";
    }

    /** A fork that dropped the on/off toggles. Absent must NOT read as "switched off". */
    public static final class NoFlagsConfig {
        public float sectionRenderDistance = 16.0f;
    }

    /** The player switched voxy's renderer off. A legitimate 0 -- and a QUIET one. */
    public static final class RenderingOffConfig {
        public boolean enabled = true;
        public boolean enableRendering = false;
        public float sectionRenderDistance = 16.0f;
    }

    /** Fields hidden behind a superclass and non-public: still readable. */
    public static class BaseConfig {
        protected int sectionRenderDistance = 8;
    }

    /** Subclass that inherits the setting. */
    public static final class InheritedConfig extends BaseConfig {
        public boolean enabled = true;
    }

    @Before
    public void resetWarnings() {
        LodWarnings.reset();
    }

    @Test
    public void floatFieldGivesVoxysDefaultRadius() {
        assertEquals(8192, VoxyConfigReader.radiusBlocks(new UpstreamConfig()));
    }

    @Test
    public void intFieldReadsTheSameRadiusAsFloat() {
        // THE REGRESSION: this used to throw NoSuchFieldError, get swallowed, and collapse to 256.
        assertEquals(8192, VoxyConfigReader.radiusBlocks(new IntConfig()));
        assertEquals(VoxyConfigReader.radiusBlocks(new UpstreamConfig()),
                VoxyConfigReader.radiusBlocks(new IntConfig()));
        assertFalse("a field we CAN read must not warn",
                LodWarnings.saidAlready("voxy-render-distance-field"));
    }

    @Test
    public void doubleAndLongFieldsAlsoRead() {
        assertEquals(8192, VoxyConfigReader.radiusBlocks(new DoubleConfig()));
        assertEquals(8192, VoxyConfigReader.radiusBlocks(new LongConfig()));
    }

    @Test
    public void fractionalSectionsRoundToBlocks() {
        final UpstreamConfig cfg = new UpstreamConfig();
        cfg.sectionRenderDistance = 4.5f;
        assertEquals(2304, VoxyConfigReader.radiusBlocks(cfg));
    }

    @Test
    public void absentFieldIsZeroAND_WARNS() {
        assertEquals(0, VoxyConfigReader.radiusBlocks(new NoFieldConfig()));
        assertTrue("a 32x radius collapse must never be silent",
                LodWarnings.saidAlready("voxy-render-distance-field"));
    }

    @Test
    public void aFieldThatIsNotANumberIsZeroAndWarns() {
        assertEquals(0, VoxyConfigReader.radiusBlocks(new NotANumberConfig()));
        assertTrue(LodWarnings.saidAlready("voxy-render-distance-field"));
    }

    @Test
    public void theWarningIsSaidOnlyOnce() {
        VoxyConfigReader.radiusBlocks(new NoFieldConfig());
        assertTrue(LodWarnings.saidAlready("voxy-render-distance-field"));
        // Second call must not re-warn -- once() is the contract; a per-chunk warning would bury the log.
        LodWarnings.reset();
        assertFalse(LodWarnings.saidAlready("voxy-render-distance-field"));
    }

    @Test
    public void missingFlagsDoNotMeanDisabled() {
        assertEquals(8192, VoxyConfigReader.radiusBlocks(new NoFlagsConfig()));
    }

    @Test
    public void renderingSwitchedOffIsAQuietZero() {
        assertEquals(0, VoxyConfigReader.radiusBlocks(new RenderingOffConfig()));
        assertFalse("the player turning voxy's renderer off is not a fault",
                LodWarnings.saidAlready("voxy-render-distance-field"));
    }

    @Test
    public void nonPositiveDistanceIsAQuietZero() {
        final IntConfig cfg = new IntConfig();
        cfg.sectionRenderDistance = 0;
        assertEquals(0, VoxyConfigReader.radiusBlocks(cfg));
        assertFalse(LodWarnings.saidAlready("voxy-render-distance-field"));
    }

    @Test
    public void nullConfigIsAQuietZero() {
        assertEquals(0, VoxyConfigReader.radiusBlocks(null));
        assertFalse(LodWarnings.saidAlready("voxy-render-distance-field"));
    }

    @Test
    public void inheritedNonPublicFieldIsStillRead() {
        assertEquals(4096, VoxyConfigReader.radiusBlocks(new InheritedConfig()));
    }

    @Test
    public void numberReadsEveryPrimitiveWidth() {
        assertEquals(OptionalDouble.of(16.0), VoxyConfigReader.number(new IntConfig(), "sectionRenderDistance"));
        assertEquals(OptionalDouble.of(16.0), VoxyConfigReader.number(new LongConfig(), "sectionRenderDistance"));
        assertTrue(VoxyConfigReader.number(new UpstreamConfig(), "nope").isEmpty());
        assertTrue(VoxyConfigReader.number(new UpstreamConfig(), "enabled").isEmpty());
    }

    @Test
    public void flagFallsBackWhenTheFieldIsGone() {
        assertTrue(VoxyConfigReader.flag(new NoFlagsConfig(), "enabled", true));
        assertFalse(VoxyConfigReader.flag(new NoFlagsConfig(), "enabled", false));
        assertFalse(VoxyConfigReader.flag(new RenderingOffConfig(), "enableRendering", true));
    }

    @Test
    public void staticFieldIsReadByNameOrNull() {
        assertEquals("CONFIG", VoxyConfigReader.staticField(Holder.class, "CONFIG"));
        assertNull(VoxyConfigReader.staticField(Holder.class, "GONE"));
    }

    /** Stands in for voxy's {@code VoxyConfig.CONFIG} holder. */
    public static final class Holder {
        public static final String CONFIG = "CONFIG";
    }
}
