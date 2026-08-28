package com.kishku7.chunksmith.shape;

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.util.Vector2;

public class Ellipse extends AbstractEllipse {
    public Ellipse(Selection selection, boolean chunkAligned) {
        super(selection, chunkAligned);
    }

    @Override
    public boolean isBounding(double x, double z) {
        return (Math.pow(x - centerX, 2) / Math.pow(radiusX, 2)) + (Math.pow(z - centerZ, 2) / Math.pow(radiusZ, 2)) <= 1;
    }

    @Override
    public String name() {
        return ShapeType.ELLIPSE;
    }

    @Override
    public Vector2 radii() {
        return Vector2.of(radiusX, radiusZ);
    }
}
