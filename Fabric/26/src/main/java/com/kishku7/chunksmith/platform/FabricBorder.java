package com.kishku7.chunksmith.platform;

import net.minecraft.world.level.border.WorldBorder;
import com.kishku7.chunksmith.platform.util.Vector2;
import com.kishku7.chunksmith.shape.ShapeType;

public class FabricBorder implements Border {
    private final WorldBorder worldBorder;

    public FabricBorder(final WorldBorder worldBorder) {
        this.worldBorder = worldBorder;
    }

    @Override
    public Vector2 getCenter() {
        return Vector2.of(worldBorder.getCenterX(), worldBorder.getCenterZ());
    }

    @Override
    public double getRadiusX() {
        return worldBorder.getSize() / 2d;
    }

    @Override
    public double getRadiusZ() {
        return worldBorder.getSize() / 2d;
    }

    @Override
    public String getShape() {
        return ShapeType.SQUARE;
    }
}
