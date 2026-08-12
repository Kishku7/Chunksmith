package com.kishku7.chunksmith.lod.client;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The guard that keeps the house rule true for the LOD CLIENT's config file.
 *
 * <p>Rule (2026-08-11): every setting in a config file is settable from a command.
 * {@code ConfigSettingsCoverageTest} enforces it for {@code config/chunksmith/config.json} by reflecting
 * over the JSON config model. That test was green while these two keys had no command at all -- because
 * the client's {@code chunksmith-lod.properties} is a different file that its reflection can never see.
 *
 * <p><b>An automated check only proves what it actually inspects.</b> That is the whole reason this second
 * test exists rather than a line being added to the first one: a registry per config file, a coverage test
 * per registry, each naming the surface it covers.
 *
 * <p>It reads the {@code KEY_*} constants off {@link CsLodClientConfig} by reflection and asserts each one
 * is reachable through {@code /cslod set}. Add a key to the client config and forget the command, and this
 * fails by name.
 */
public class CsLodClientSettingsCoverageTest {

    /**
     * {@code KEY_*} constants that are deliberately NOT settings.
     *
     * <p>Empty today, and that is the point: the exclusion list is explicit, so excluding something is a
     * visible decision with a reason next to it rather than an omission.
     */
    private static final Set<String> NOT_SETTINGS = Set.of();

    /** Every config KEY the client config declares, read off its {@code KEY_*} constants. */
    private static List<String> declaredKeys() throws IllegalAccessException {
        final List<String> keys = new ArrayList<>();
        for (final Field field : CsLodClientConfig.class.getDeclaredFields()) {
            if (!field.getName().startsWith("KEY_")) {
                continue;
            }
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            keys.add((String) field.get(null));
        }
        return keys;
    }

    @Test
    public void everyClientConfigKeyIsReachableFromACommand() throws IllegalAccessException {
        final List<String> declared = declaredKeys();
        assertFalse("reflection found no KEY_* constants -- the test would pass vacuously", declared.isEmpty());

        final List<String> missing = new ArrayList<>();
        for (final String key : declared) {
            if (NOT_SETTINGS.contains(key)) {
                continue;
            }
            if (CsLodClientSettings.find(key).isEmpty()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            fail("client config keys with no /cslod set entry (add them to CsLodClientSettings, or to "
                    + "NOT_SETTINGS with a reason): " + missing);
        }
    }

    /** The mirror of the above: a setting that names a key the config does not have is a typo. */
    @Test
    public void everyCommandSettingNamesARealClientConfigKey() throws IllegalAccessException {
        final List<String> declared = declaredKeys();
        final List<String> unknown = new ArrayList<>();
        for (final CsLodClientSettings.Setting setting : CsLodClientSettings.all()) {
            if (!declared.contains(setting.name())) {
                unknown.add(setting.name());
            }
        }
        if (!unknown.isEmpty()) {
            fail("/cslod set exposes names that are not client config keys: " + unknown);
        }
    }

    @Test
    public void lookupIsCaseInsensitive() {
        assertTrue(CsLodClientSettings.find("SYNC-INTERVAL-SECONDS").isPresent());
        assertTrue(CsLodClientSettings.find("Reinject-On-Join").isPresent());
    }

    @Test
    public void namesAreUniqueSoLookupIsUnambiguous() {
        final List<String> seen = new ArrayList<>();
        for (final CsLodClientSettings.Setting setting : CsLodClientSettings.all()) {
            final String lower = setting.name().toLowerCase(Locale.ROOT);
            if (seen.contains(lower)) {
                fail("duplicate setting name: " + setting.name());
            }
            seen.add(lower);
        }
    }

    @Test
    public void booleanSettingsOfferCompletions() {
        final CsLodClientSettings.Setting reinject =
                CsLodClientSettings.find(CsLodClientConfig.KEY_REINJECT).orElseThrow();
        assertTrue(reinject.kind().completions().contains("true"));
        assertTrue(reinject.kind().completions().contains("false"));
    }

    @Test
    public void theIntervalIsClampedOnWriteNotOnlyOnRead() {
        final CsLodClientSettings.Setting sync =
                CsLodClientSettings.find(CsLodClientConfig.KEY_SYNC_SECONDS).orElseThrow();
        assertTrue(sync.write("1"));
        assertEquals("a value under the floor must be STORED as the floor, not merely read back as it",
                Integer.toString(CsLodClientConfig.MIN_SYNC_SECONDS), sync.read());

        assertTrue(sync.write("600"));
        assertEquals("600", sync.read());
    }

    @Test
    public void aValueOfTheWrongShapeIsRefusedRatherThanBecomingADefault() {
        final CsLodClientSettings.Setting sync =
                CsLodClientSettings.find(CsLodClientConfig.KEY_SYNC_SECONDS).orElseThrow();
        assertTrue(sync.write("120"));
        assertFalse(sync.write("soon"));
        assertEquals("a refused write must leave the previous value alone", "120", sync.read());

        // Out of int range is a shape error too: narrowing it silently would store the wrapped value.
        assertFalse(sync.write("4000000000"));
        assertEquals("120", sync.read());

        final CsLodClientSettings.Setting reinject =
                CsLodClientSettings.find(CsLodClientConfig.KEY_REINJECT).orElseThrow();
        assertTrue(reinject.write("true"));
        assertEquals("true", reinject.read());
        assertFalse(reinject.write("yes-please"));
        assertEquals("true", reinject.read());

        assertTrue(reinject.write("false"));
    }
}
