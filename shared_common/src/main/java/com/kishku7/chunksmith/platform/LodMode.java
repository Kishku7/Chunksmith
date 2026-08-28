package com.kishku7.chunksmith.platform;

import java.util.Locale;

public enum LodMode {
    AUTO,
    ON,
    OFF;

    public static LodMode parse(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return AUTO;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto":
                return AUTO;
            case "true":
            case "on":
            case "yes":
                return ON;
            case "false":
            case "off":
            case "no":
                return OFF;
            default:
                return null;
        }
    }
}
