package com.kishku7.chunksmith.util;

/** Small reflection helpers shared across platforms. */
public final class Reflection {
    private Reflection() {
    }

    /** True if the named class is present on the classpath. */
    public static boolean classExists(final String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}