package com.kishku7.chunksmith.lod;

/**
 * Publishes the per-world {@link CsLodPresenceIndex} to MC-agnostic shared code -- the same seam as
 * {@link LodSinks}. {@code GenerationTask} lives in shared_common and must decide whether a chunk still
 * needs its LOD built, but the only class that knows whether LOD generation is active, and where the
 * store lives, is {@code LodSupport}: a per-cell, MC-typed, cog-generated class it cannot see.
 *
 * <p><b>This is also what keeps the plugin cells out of it.</b> Bukkit/Paper/Folia have no LOD pipeline
 * at all and nothing to call {@link #setProvider}, so the provider stays null there -- as it does on a
 * loader cell whose {@code lodEnabled} tristate resolves to OFF. Null means {@link #indexFor} returns
 * null and {@code GenerationTask} takes the byte-for-byte path it took before this feature existed.
 */
public final class LodPresence {

    /** Resolves the presence index for a world, or null when LOD generation is not active for it. */
    @FunctionalInterface
    public interface Provider {
        /**
         * @param worldName as {@code World.getName()} reports it -- on every loader cell the dimension
         *                  id ({@code minecraft:overworld}), the key {@code LodSupport} maps its sinks by
         */
        CsLodPresenceIndex indexFor(String worldName);
    }

    private static volatile Provider provider;

    private LodPresence() {
    }

    /** Publish the provider. Pass null to unpublish (server stop). */
    public static void setProvider(final Provider value) {
        provider = value;
    }

    public static CsLodPresenceIndex indexFor(final String worldName) {
        final Provider current = provider;
        return current == null ? null : current.indexFor(worldName);
    }
}
