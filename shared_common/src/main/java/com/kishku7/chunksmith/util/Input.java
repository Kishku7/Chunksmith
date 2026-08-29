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

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.iterator.PatternType;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.shape.ShapeType;

import java.util.Optional;

public final class Input {
    private Input() {
    }

    public static Optional<World> tryWorld(Chunksmith chunky, String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        return chunky.getServer().getWorld(input);
    }

    public static Optional<String> tryPattern(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        String inputLower = input.toLowerCase();
        if (PatternType.ALL.contains(inputLower)) {
            return Optional.of(inputLower);
        }
        return Optional.empty();
    }

    public static Optional<String> tryShape(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        String inputLower = input.toLowerCase();
        if (ShapeType.all().contains(inputLower)) {
            return Optional.of(inputLower);
        }
        return Optional.empty();
    }

    /**
     * Parses a boolean strictly. Only "true" or "false", either case, with surrounding space ignored.
     *
     * <p>It used to be {@code Boolean.parseBoolean}, which answers FALSE for every
     * string that is not "true" and never reports a problem. Through {@code /cs set}
     * that meant a typo did not fail, it silently turned the setting OFF. {@code /cs
     * set silent yes} disabled silent mode and said it had been set. The 3.2.4 notes
     * claim a value that cannot be understood is refused rather than quietly
     * becoming a default; true for the numbers, not for the booleans. Found by the
     * {@code /cslod set} coverage test, asserting the documented behaviour and
     * getting the real one.
     *
     * <p>{@code TaskLoader} reads a stored property through here with {@code
     * orElse(false)}, which is unchanged by this: a malformed stored value was false
     * before and is false now.
     */
    public static Optional<Boolean> tryBoolean(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        String value = input.trim();
        if (value.equalsIgnoreCase("true")) {
            return Optional.of(Boolean.TRUE);
        }
        if (value.equalsIgnoreCase("false")) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    public static Optional<Integer> tryInteger(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(input));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<Integer> tryIntegerSuffixed(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        int last = input.length() - 1;
        return suffixValue(input.charAt(last))
                .map(suffixValue -> tryInteger(input.substring(0, last)).map(i -> i * suffixValue))
                .orElse(tryInteger(input));
    }

    public static Optional<Double> tryDouble(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(input));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<Double> tryDoubleSuffixed(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        int last = input.length() - 1;
        return suffixValue(input.charAt(last))
                .map(suffixValue -> tryDouble(input.substring(0, last)).map(d -> d * suffixValue))
                .orElse(tryDouble(input));
    }

    public static Optional<Long> tryLong(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(input));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<Integer> trySign(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        char sign = input.charAt(0);
        return switch (sign) {
            case '-' -> Optional.of(-1);
            case '+' -> Optional.of(1);
            default -> Optional.empty();
        };
    }

    public static boolean isPastWorldLimit(double value) {
        return Math.abs(value) > 3e7;
    }

    public static String checkLanguage(String language) {
        return Translator.isValidLanguage(language) ? language : "en";
    }

    private static Optional<Integer> suffixValue(char suffix) {
        return switch (Character.toLowerCase(suffix)) {
            case 'c' -> Optional.of(16);
            case 'r' -> Optional.of(512);
            case 'k' -> Optional.of(1000);
            default -> Optional.empty();
        };
    }
}
