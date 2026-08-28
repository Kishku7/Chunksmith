package com.kishku7.chunksmith.util;

public final class Reflection {
    private Reflection() {
    }

    public static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}