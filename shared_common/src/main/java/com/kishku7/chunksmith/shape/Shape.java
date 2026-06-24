package com.kishku7.chunksmith.shape;

@FunctionalInterface
public interface Shape {
    boolean isBounding(double x, double z);

    default String name() {
        return "shape";
    }
}
