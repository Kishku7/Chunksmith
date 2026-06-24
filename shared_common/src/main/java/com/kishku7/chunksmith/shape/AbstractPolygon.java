package com.kishku7.chunksmith.shape;

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.util.Vector2;

import java.util.List;

public abstract class AbstractPolygon extends AbstractShape {
    protected AbstractPolygon(final Selection selection, final boolean chunkAligned) {
        super(selection, chunkAligned);
    }

    public abstract List<Vector2> points();
}
