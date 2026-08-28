package com.kishku7.chunksmith.util;

import java.util.BitSet;

/**
 * Decides where, just behind the generation front, to briefly re-load a neighbourhood so that other mods
 * can finish work they could not do while the pregen was racing past.
 *
 * <p>{@link ChunkSettleWindow} keeps a chunk loaded until its eight neighbours exist, which is enough
 * for a small structure and nowhere near enough for a big one: Millenaire villages want a 90-block
 * radius, and on a real run 5,482 village placements were still refused for want of loaded ground.
 * Widening the hold does not scale: the held band follows the whole sweep edge, so at a seven-chunk
 * radius a modest pregen would hold some 11,000 chunks.
 *
 * <p>So instead we let it go and come back: a small window slides along behind the front, loads a
 * neighbourhood, gives the server a moment to tick, and moves on. Peak cost is one window and does not
 * grow with the size of the run. Chunks are re-read from disk rather than regenerated, so this is I/O,
 * not worldgen. It trails rather than waiting for the end because ground more than a radius behind the
 * front is finished and will never change, and the square and spiral patterns have no clean rings.
 *
 * <p>Stops sit on a grid rather than at every chunk; a window centred every {@code radius} chunks
 * covers the whole area at a fraction of the visits: for a 36,000-chunk run at radius 7, roughly 700
 * stops. A stop becomes eligible only once its whole window is generated, because loading a window that
 * overlaps ungenerated ground would GENERATE it, off-pattern and outside the task's accounting.
 * Generated chunks live in a {@link BitSet} over the task bounds: exact, one bit per chunk, a few kB for
 * a large pregen.
 *
 * <p>The caller owns the tickets. This says where and when; it never loads a chunk itself.
 */
public final class SettleSweep {

    private final int minChunkX;
    private final int minChunkZ;
    private final int width;
    private final int height;
    private final int radius;

    private final BitSet generated;
    private final BitSet swept;

    /** Scan cursor over grid stops, so a pass never re-tests the whole area from the start. */
    private int cursor;

    private int stopsIssued;

    /**
     * @param radius window radius in chunks -- large enough to cover the biggest footprint we want to
     *               let another mod build
     */
    public SettleSweep(final int minChunkX, final int minChunkZ, final int width, final int height,
                       final int radius) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.radius = Math.max(1, radius);
        this.generated = new BitSet(Math.max(1, this.width * this.height));
        this.swept = new BitSet(Math.max(1, this.width * this.height));
    }

    /** Records that a chunk now exists on disk. Out-of-bounds chunks are ignored, not an error. */
    public void markGenerated(int chunkX, int chunkZ) {
        int index = index(chunkX, chunkZ);
        if (index >= 0) {
            this.generated.set(index);
        }
    }

    /**
     * Returns the next window centre as {@code {chunkX, chunkZ}}, or {@code null} if none is ready yet. Ready
     * means every chunk in the window has been generated. See the class doc on why a partially
     * generated window must never be loaded. Returns each stop at most once.
     *
     * @return the next window centre, or {@code null} when none is ready
     */
    public int[] nextStop() {
        int stops = stopCount();
        for (int scanned = 0; scanned < stops; scanned++) {
            int slot = this.cursor;
            this.cursor = (this.cursor + 1) % Math.max(1, stops);
            int stopsX = stopsPerRow();
            int gx = slot % stopsX;
            int gz = slot / stopsX;
            int chunkX = this.minChunkX + Math.min(gx * this.radius, this.width - 1);
            int chunkZ = this.minChunkZ + Math.min(gz * this.radius, this.height - 1);
            int index = index(chunkX, chunkZ);
            if (index < 0 || this.swept.get(index)) {
                continue;
            }
            if (windowGenerated(chunkX, chunkZ)) {
                this.swept.set(index);
                this.stopsIssued++;
                return new int[] { chunkX, chunkZ };
            }
        }
        return null;
    }

    /**
     * Checks whether every chunk of this window is generated. Clamped to the task bounds, since an edge
     * window legitimately hangs over ground the task was never asked to generate, and waiting for chunks
     * nobody will make would strand every edge stop forever.
     *
     * @return true when every chunk of the window has been generated
     */
    public boolean windowGenerated(int chunkX, int chunkZ) {
        for (int x = chunkX - this.radius; x <= chunkX + this.radius; x++) {
            for (int z = chunkZ - this.radius; z <= chunkZ + this.radius; z++) {
                int index = index(x, z);
                if (index >= 0 && !this.generated.get(index)) {
                    return false;
                }
            }
        }
        return true;
    }

    public int stopCount() {
        return stopsPerRow() * stopsPerColumn();
    }

    public int stopsIssued() {
        return this.stopsIssued;
    }

    public boolean isComplete() {
        return this.stopsIssued >= stopCount();
    }

    public int radius() {
        return this.radius;
    }

    private int stopsPerRow() {
        return this.width <= 0 ? 0 : (this.width + this.radius - 1) / this.radius;
    }

    private int stopsPerColumn() {
        return this.height <= 0 ? 0 : (this.height + this.radius - 1) / this.radius;
    }

    /** Flat index into the bitsets, or -1 when the chunk is outside the task's bounds. */
    private int index(int chunkX, int chunkZ) {
        int x = chunkX - this.minChunkX;
        int z = chunkZ - this.minChunkZ;
        if (x < 0 || z < 0 || x >= this.width || z >= this.height) {
            return -1;
        }
        return z * this.width + x;
    }
}
