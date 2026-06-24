package com.kishku7.chunksmith.shape;

import org.junit.Test;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.iterator.ChunkIterator;
import com.kishku7.chunksmith.iterator.ChunkIteratorFactory;
import com.kishku7.chunksmith.util.ChunkCoordinate;

import static org.junit.Assert.assertEquals;

/**
 * This test checks each shape to ensure that the number of chunks they generate is correct.
 */
public class ShapeTest {
    private static final Selection.Builder SELECTION = Selection.builder(null, null).center(-500, 500).radiusX(1000).radiusZ(500);

    @Test
    public void square() {
        testShape("square", 16129);
    }

    @Test
    public void circle() {
        testShape("circle", 12645);
    }

    @Test
    public void triangle() {
        testShape("triangle", 8065);
    }

    @Test
    public void diamond() {
        testShape("diamond", 8065);
    }

    @Test
    public void pentagon() {
        testShape("pentagon", 9593);
    }

    @Test
    public void star() {
        testShape("star", 4518);
    }

    @Test
    public void rectangle() {
        testShape("rectangle", 8255);
    }

    @Test
    public void ellipse() {
        testShape("ellipse", 6503);
    }

    private void testShape(final String type, final int expected) {
        final Selection s = SELECTION.shape(type).build();
        final ChunkIterator chunkIterator = ChunkIteratorFactory.getChunkIterator(s);
        final Shape shape = ShapeFactory.getShape(s);
        int generated = 0;
        while (chunkIterator.hasNext()) {
            final ChunkCoordinate chunkCoordinate = chunkIterator.next();
            final int xChunkCenter = (chunkCoordinate.x() << 4) + 8;
            final int zChunkCenter = (chunkCoordinate.z() << 4) + 8;
            if (shape.isBounding(xChunkCenter, zChunkCenter)) {
                ++generated;
            }
        }
        assertEquals(expected, generated);
    }
}
