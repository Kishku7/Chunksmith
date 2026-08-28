package com.kishku7.chunksmith.platform.impl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The settle settings as seen through {@code /cs settle}.
 *
 * <p>The house rule -- every key in the config file has a command that can set it -- is only true if the
 * SETTER half works, and a setter has two jobs the getter does not: survive a restart, and refuse a
 * value the getter would later clamp.
 *
 * <p>These tests drive {@link GsonConfig} directly rather than the command, because that is where both
 * jobs actually live; the command only forwards and reads back.
 */
public class GsonConfigSettleTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path configPath() throws IOException {
        return folder.newFolder("chunksmith").toPath().resolve("config.json");
    }

    @Test
    public void freshConfigCarriesTheDocumentedDefaults() throws IOException {
        final GsonConfig config = new GsonConfig(configPath());
        assertTrue("settle is ON by default -- off silently breaks every mod that builds on new land",
                config.isPregenSettleEnabled());
        assertEquals(40L, config.getPregenSettleDelayTicks());
        assertEquals(7, config.getPregenSettleRadius());
        assertTrue("a real platform supports settle", config.isPregenSettleSupported());
    }

    @Test
    public void freshConfigIsWrittenWithTheSettleKeys() throws IOException {
        final Path path = configPath();
        new GsonConfig(path);
        final String written = Files.readString(path);
        assertTrue("pregenSettle missing from a freshly written config", written.contains("pregenSettle"));
        assertTrue(written.contains("pregenSettleDelayTicks"));
        assertTrue(written.contains("pregenSettleRadius"));
    }

    @Test
    public void settingsSurviveAReload() throws IOException {
        final Path path = configPath();
        final GsonConfig first = new GsonConfig(path);
        first.setPregenSettleEnabled(false);
        first.setPregenSettleDelayTicks(120L);
        first.setPregenSettleRadius(11);

        final GsonConfig reloaded = new GsonConfig(path);
        assertFalse(reloaded.isPregenSettleEnabled());
        assertEquals(120L, reloaded.getPregenSettleDelayTicks());
        assertEquals(11, reloaded.getPregenSettleRadius());
    }

    @Test
    public void outOfRangeValuesAreClampedBeforeTheyReachTheFile() throws IOException {
        final Path path = configPath();
        final GsonConfig config = new GsonConfig(path);

        config.setPregenSettleDelayTicks(10_000L);
        assertEquals("delay clamps to its documented maximum", 600L, config.getPregenSettleDelayTicks());

        config.setPregenSettleDelayTicks(-5L);
        assertEquals("delay cannot go negative", 0L, config.getPregenSettleDelayTicks());

        config.setPregenSettleRadius(999);
        assertEquals("radius clamps to its documented maximum", 16, config.getPregenSettleRadius());

        config.setPregenSettleRadius(0);
        assertEquals("a radius of zero would load nothing at all", 1, config.getPregenSettleRadius());

        final GsonConfig reloaded = new GsonConfig(path);
        assertEquals("the CLAMPED value is what was persisted", 1, reloaded.getPregenSettleRadius());
        assertEquals(0L, reloaded.getPregenSettleDelayTicks());
    }

    @Test
    public void aConfigFromAnEarlierVersionReadsAsTheDefaults() throws IOException {
        final Path path = configPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\n  \"version\": 2,\n  \"language\": \"en\",\n  \"silent\": false\n}\n");

        final GsonConfig config = new GsonConfig(path);
        assertTrue(config.isPregenSettleEnabled());
        assertEquals(40L, config.getPregenSettleDelayTicks());
        assertEquals(7, config.getPregenSettleRadius());
    }
}
