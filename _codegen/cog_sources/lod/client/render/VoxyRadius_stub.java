package com.kishku7.chunksmith.lod.client.render;

/**
 * The voxy render-distance seam on every cell that has no voxy -- no voxy, no distance to read.
 *
 * <p>Seam twin of the real {@code VoxyRadius}: same package, same name, same signature, so the shared
 * {@code Renderers.configuredRadiusBlocks()} compiles and runs on every cell. See {@link VoxyTarget} for
 * why the adapter does not exist here.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/voxy. Edit ONLY there.
 */
public final class VoxyRadius {

    private VoxyRadius() {
    }

    /**
     * Always 0 -- "cannot be read", which is exactly what {@code Renderers} expects when a renderer is not
     * there. The radius this cell announces therefore comes from Distant Horizons alone.
     */
    public static int blocks() {
        return 0;
    }
}
