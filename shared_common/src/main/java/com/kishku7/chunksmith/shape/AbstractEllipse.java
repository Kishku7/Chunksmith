package com.kishku7.chunksmith.shape;

import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.util.Vector2;

public abstract class AbstractEllipse extends AbstractShape {
    protected AbstractEllipse(Selection selection, boolean chunkAligned) {
        super(selection, chunkAligned);
    }

    public abstract Vector2 radii();
}
