package com.kishku7.chunksmith.platform;

/**
 * The tristate behind the {@code lodEnabled} config key.
 *
 * <p>{@code lodEnabled} is NOT a boolean. It is {@code "auto"} (the default), {@code true}, or
 * {@code false}:
 *
 * <ul>
 *   <li>{@link #AUTO} -- Chunksmith decides. LOD generation switches itself ON when an LOD renderer
 *       is present in the JVM (Distant Horizons, voxy, or a voxy fork), and ON for a DEDICATED
 *       server, whose whole reason to build a store is to serve it to Chunksmith-Client players who
 *       have the renderer that the server never will. Otherwise OFF: nothing can draw it.</li>
 *   <li>{@link #ON} -- always generate. An explicit operator decision; never overridden.</li>
 *   <li>{@link #OFF} -- never generate. An explicit operator decision; never overridden, not even
 *       with a renderer installed.</li>
 * </ul>
 *
 * <p>A plain JSON boolean is still accepted and still means exactly what it always meant, so a config
 * written by an older Chunksmith keeps working unchanged and is never silently rewritten.
 */
public enum LodMode {
    AUTO,
    ON,
    OFF;

    /**
     * Parse a config value. Accepts {@code auto} (or null/blank), {@code true}/{@code on}/{@code yes},
     * and {@code false}/{@code off}/{@code no}, case-insensitively. Anything else returns null so the
     * caller can complain about it once and fall back to {@link #AUTO} -- a typo must never be read as
     * a silent "off".
     */
    public static LodMode parse(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return AUTO;
        }
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
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
