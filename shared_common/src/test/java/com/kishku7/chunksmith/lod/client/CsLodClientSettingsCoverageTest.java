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
 * The guard for the LOD CLIENT's config file: every setting in a config file is settable from a command.
 *
 * <p>{@code ConfigSettingsCoverageTest} enforces that rule for {@code config/chunksmith/config.json} by
 * reflecting over the JSON config model. It was green while these two keys had no command at all,
 * because the client's {@code chunksmith-lod.properties} is a different file that its reflection can
 * never see. Hence a second test rather than a line added to the first: a registry per config file, a
 * coverage test per registry, each naming the surface it covers.
 *
 * <p>It reads the {@code KEY_*} constants off {@link CsLodClientConfig} by reflection and asserts each
 * one is reachable through {@code /cslod set}. Add a key to the client config and forget the command,
 * and this fails by name.
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
    public void everyClientConfigKeyHasACommand() throws IllegalAccessException {
        final List<String> declared = declaredKeys();
        assertFalse("no KEY_* constants found", declared.isEmpty());

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
    public void everySettingNamesARealKey() throws IllegalAccessException {
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
    public void namesAreUnique() {
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
    public void clampedOnWriteNotJustOnRead() {
        final CsLodClientSettings.Setting sync =
                CsLodClientSettings.find(CsLodClientConfig.KEY_SYNC_SECONDS).orElseThrow();
        assertTrue(sync.write("1"));
        assertEquals("not stored as the floor",
                Integer.toString(CsLodClientConfig.MIN_SYNC_SECONDS), sync.read());

        assertTrue(sync.write("600"));
        assertEquals("600", sync.read());
    }

    @Test
    public void aBadValueIsRefusedNotDefaulted() {
        final CsLodClientSettings.Setting sync =
                CsLodClientSettings.find(CsLodClientConfig.KEY_SYNC_SECONDS).orElseThrow();
        assertTrue(sync.write("120"));
        assertFalse(sync.write("soon"));
        assertEquals("refused write changed the value", "120", sync.read());

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
