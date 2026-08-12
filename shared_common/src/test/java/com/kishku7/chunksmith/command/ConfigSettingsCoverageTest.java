package com.kishku7.chunksmith.command;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The guard that keeps the house rule true over time.
 *
 * <p>Rule (2026-08-11): every setting in the config file is settable from a command. That rule was
 * broken the moment it was written -- nine of eleven keys had no command -- because nothing checked. A rule
 * enforced only by memory is a rule that decays silently at the exact moment somebody is busy shipping
 * something else.
 *
 * <p>So this test reads the config MODEL by reflection and asserts that every field in it is either
 * reachable through {@code /cs set} or on a short, explicit exclusion list. Add a key to the config and
 * forget the command, and this fails by name.
 */
public class ConfigSettingsCoverageTest {

    /**
     * Fields that are deliberately NOT operator settings.
     *
     * <p>{@code version} is the config schema number -- letting it be set would invite a file claiming to
     * be a shape it is not. {@code tasks} is the saved task list, which has its own commands
     * ({@code /cs start}, {@code /cs cancel}, {@code /cs continue}) and is not a scalar setting.
     */
    private static final Set<String> NOT_SETTINGS = Set.of("version", "tasks");

    private static List<String> configModelFields() throws ClassNotFoundException {
        final Class<?> model = Class.forName("com.kishku7.chunksmith.platform.impl.GsonConfig$ConfigModel");
        final List<String> names = new ArrayList<>();
        for (final Field field : model.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }

    @Test
    public void everyConfigKeyIsReachableFromACommand() throws ClassNotFoundException {
        final List<String> missing = new ArrayList<>();
        for (final String field : configModelFields()) {
            if (NOT_SETTINGS.contains(field)) {
                continue;
            }
            if (ConfigSettings.find(field).isEmpty()) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            fail("config keys with no /cs set entry (add them to ConfigSettings, or to NOT_SETTINGS "
                    + "with a reason): " + missing);
        }
    }

    /** The mirror of the above: a setting that names a key the config does not have is a typo. */
    @Test
    public void everyCommandSettingNamesARealConfigKey() throws ClassNotFoundException {
        final List<String> fields = configModelFields();
        final List<String> unknown = new ArrayList<>();
        for (final ConfigSetting setting : ConfigSettings.all()) {
            if (!fields.contains(setting.name())) {
                unknown.add(setting.name());
            }
        }
        if (!unknown.isEmpty()) {
            fail("/cs set exposes names that are not config keys: " + unknown);
        }
    }

    @Test
    public void lookupIsCaseInsensitiveBecauseNobodyTypesCamelCaseCorrectly() {
        assertTrue(ConfigSettings.find("pregensettleradius").isPresent());
        assertTrue(ConfigSettings.find("PREGENSETTLERADIUS").isPresent());
        assertTrue(ConfigSettings.find("pregenSettleRadius").isPresent());
    }

    @Test
    public void booleanAndTristateSettingsOfferCompletions() {
        final ConfigSetting settle = ConfigSettings.find("pregenSettle").orElseThrow();
        assertTrue(settle.kind().completions().contains("true"));

        final ConfigSetting lod = ConfigSettings.find("lodEnabled").orElseThrow();
        assertTrue("lodEnabled is a tristate, so auto must be offered",
                lod.kind().completions().contains("auto"));
    }

    @Test
    public void namesAreUniqueSoLookupIsUnambiguous() {
        final List<String> seen = new ArrayList<>();
        for (final ConfigSetting setting : ConfigSettings.all()) {
            final String lower = setting.name().toLowerCase(java.util.Locale.ROOT);
            if (seen.contains(lower)) {
                fail("duplicate setting name: " + setting.name());
            }
            seen.add(lower);
        }
    }
}
