/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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

package com.kishku7.chunksmith.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Translator {
    private static final Map<String, String> fallbackTranslations;
    private static Map<String, String> translations = Map.of();

    static {
        fallbackTranslations = load("en");
    }

    private Translator() {
    }

    public static void setLanguage(String language) {
        translations = load(language);
    }

    public static boolean isValidLanguage(String language) {
        return Translator.class.getClassLoader().getResource("lang/" + language + ".json") != null;
    }

    public static String translateKey(String key, boolean prefixed, Object... args) {
        final StringBuilder translation = new StringBuilder();
        final String message = translations.getOrDefault(key, fallbackTranslations.getOrDefault(key, key));
        if (prefixed) {
            translation.append("[Chunksmith] ");
        }
        translation.append(String.format(message, args));
        return translation.toString();
    }

    public static String translate(String key, Object... args) {
        return translateKey(key, false, args);
    }

    public static void addCustomTranslation(String key, String message) {
        fallbackTranslations.put(key, message);
    }

    private static Map<String, String> load(String language) {
        final InputStream input = Translator.class.getClassLoader().getResourceAsStream("lang/" + language + ".json");
        if (input != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                final StringBuilder lang = new StringBuilder();
                String s;
                while ((s = reader.readLine()) != null) {
                    lang.append(s);
                }
                return new Gson().fromJson(lang.toString(), new TypeToken<HashMap<String, String>>() {
                }.getType());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return Map.of();
    }
}
