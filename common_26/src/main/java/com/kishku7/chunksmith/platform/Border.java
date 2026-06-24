package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.platform.util.Vector2;

public interface Border {
    Vector2 getCenter();

    double getRadiusX();

    double getRadiusZ();

    String getShape();
}
