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

    /** The DNS limit, and the point past which a config value is a paste accident rather than a host. */
    private static final int MAX_HOST_LENGTH = 253;

    /** The DNS limit on one dot-separated label. */
    private static final int MAX_LABEL_LENGTH = 63;
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

    /**
     * Returns a host or IP literal fit to put in a config file, or the empty
     * string for anything that is not one.
     *
     * <p>Empty is the "unset" value for both backchannel address keys, so a
     * rejected value lands on the documented default rather than on a
     * near-miss: an operator who typed something wrong gets the behaviour they
     * had before they typed it, which is a working server, and the key they
     * meant to set is visibly still empty when they go looking.
     *
     * <p>Deliberately a syntax check and not a resolve. {@code InetAddress}
     * would do DNS, and this runs on the main thread from {@code /cs set} and
     * again on every config read; a name that is slow to resolve would stall
     * the server, and one that is temporarily unresolvable would silently erase
     * a correct setting. Whether the address is reachable is answered by the
     * bind attempt and by the client's own probe, both of which report it.
     *
     * <p>Accepts a bracketed IPv6 literal ({@code [::]}) unbracketed too, since
     * that is how an operator writes it in a config file even though a URL
     * needs the brackets; the caller adds them back when building a URL.
     */
    public static String checkHost(String host) {
        if (host == null) {
            return "";
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_HOST_LENGTH) {
            return "";
        }
        // An IPv6 literal, bracketed or not: hex groups, colons, and a possible zone id.
        String bare = trimmed.startsWith("[") && trimmed.endsWith("]")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
        if (bare.indexOf(':') >= 0) {
            return bare.matches("[0-9A-Fa-f:.%]+") ? bare : "";
        }

        // A single TRAILING DOT is the DNS root and makes a name fully qualified. It is legal, an
        // operator may well paste one, and InetAddress accepts it -- but it would otherwise split
        // into an empty final label and be refused. Strip it for validation; keep it on the way out
        // so what comes back is what they typed.
        String labelsPart = bare.endsWith(".") ? bare.substring(0, bare.length() - 1) : bare;
        if (labelsPart.isEmpty()) {
            return "";
        }

        String[] labels = labelsPart.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                return "";
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                return "";
            }
            // UNDERSCORES are accepted deliberately (mod_support #26). A strict RFC-1123 HOSTNAME may
            // not contain one, but DNS permits it in a label and real deployments use it -- the
            // reporter's server is literally "myserver_minecraft.mydomain.com". Rejecting it reset the
            // key to empty and told the operator nothing.
            if (!label.matches("[0-9A-Za-z_-]+")) {
                return "";
            }
        }

        // A NAME WHOSE LAST LABEL IS ALL DIGITS IS NOT A HOSTNAME (RFC 1123 2.1) -- that shape is
        // reserved so a name can never be confused with an address. So anything ending in digits has
        // to BE a valid address, or it is nothing.
        //
        // This is what stopped "256.1.1.1" being accepted. Four numeric labels sail through every
        // hostname rule above -- letters-digits-hyphen, no empty label, none too long -- so the
        // validator happily stored an address that can never resolve, and the operator only found out
        // when the backchannel silently did not work. Same for "1.2.3", "1.2.3.4.5" and "example.123".
        if (isAllDigits(labels[labels.length - 1])) {
            return isDottedQuad(labels) ? bare : "";
        }
        return bare;
    }

    /**
     * Like {@link #checkHost} but also refuses a WILDCARD address, for the key that tells clients
     * where to connect.
     *
     * <p>{@code 0.0.0.0} and {@code ::} mean "every interface on this machine". That is exactly right
     * for a BIND address and meaningless as an advertised one: a client told to connect to 0.0.0.0
     * has been told nothing, and it will simply fail. Leaving the key EMPTY is how you say "each
     * client should use the address it already connected on", so a wildcard here is always a mistake
     * and never the shorthand somebody might assume it is.
     */
    public static String checkAdvertisedHost(String host) {
        String checked = checkHost(host);
        if (checked.isEmpty()) {
            return "";
        }
        if (isWildcardAddress(checked)) {
            return "";
        }
        return checked;
    }

    /** {@code 0.0.0.0} / {@code ::} / {@code 0:0:0:0:0:0:0:0} -- "every interface". */
    public static boolean isWildcardAddress(String host) {
        if (host == null) {
            return false;
        }
        String bare = host.trim();
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        if ("0.0.0.0".equals(bare) || "::".equals(bare)) {
            return true;
        }
        // The long-hand IPv6 unspecified address, and nothing else with a colon in it.
        return bare.indexOf(':') >= 0 && bare.replace(":", "").chars().allMatch(c -> c == '0')
                && !bare.replace(":", "").isEmpty();
    }

    /** Exactly four labels, each a 0-255 decimal with no leading zeros. */
    private static boolean isDottedQuad(String[] labels) {
        if (labels.length != 4) {
            return false;
        }
        for (String label : labels) {
            if (!isAllDigits(label) || label.length() > 3) {
                return false;
            }
            // "010" is rejected rather than read as 10: a leading zero means octal to some resolvers
            // and decimal to others, so the same string denotes two different hosts depending on who
            // parses it. An address that ambiguous is not one to store.
            if (label.length() > 1 && label.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(label) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
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
