package com.kishku7.chunksmith.lod.client.render;

/**
 * Seam twin of the real {@code VoxyRadius} on cells with no voxy: same package, name and signature, so the
 * shared {@code Renderers.configuredRadiusBlocks()} compiles everywhere ({@link VoxyTarget} says which).
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/voxy. Edit ONLY there.
 */
public final class VoxyRadius {

    private VoxyRadius() {
    }

    /** Always 0 -- "cannot be read", so this cell's radius comes from Distant Horizons alone. */
    public static int blocks() {
        return 0;
    }
}
