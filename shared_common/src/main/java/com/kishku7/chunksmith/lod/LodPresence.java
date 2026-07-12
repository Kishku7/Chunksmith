package com.kishku7.chunksmith.lod;

/**
 * Publishes the per-world {@link CsLodPresenceIndex} to MC-agnostic shared code.
 *
 * <p>Same seam as {@link LodSinks}, and for the same reason: {@code GenerationTask} lives in
 * shared_common and must decide whether a chunk still needs its LOD built, but the only class that
 * knows whether LOD generation is active -- and where the store lives -- is {@code LodSupport}, which
 * is a per-cell, MC-typed, cog-generated class that shared_common cannot see.
 *
 * <p><b>This is also what keeps the plugin cells out of it.</b> Bukkit/Paper/Folia have no LOD pipeline
 * at all: no {@code LodSupport}, no {@code LodInit}, nothing to call {@link #setProvider}. The provider
 * therefore stays null there, {@link #indexFor} returns null, and {@code GenerationTask} takes the
 * byte-for-byte code path it took before this feature existed. The LOD-aware branch is not disabled in
 * the plugin -- it is simply never reachable.
 *
 * <p>Null is the "LOD is off" answer everywhere, not just in the plugin: a loader cell whose
 * {@code lodEnabled} tristate resolves to OFF returns null too, so turning LOD off restores the old
 * skip behaviour exactly.
 */
public final class LodPresence {

    /** Resolves the presence index for a world, or null when LOD generation is not active for it. */
    @FunctionalInterface
    public interface Provider {
        /**
         * @param worldName the world's name as {@code World.getName()} reports it -- which on every
         *                  loader cell is the dimension id string ({@code minecraft:overworld}), the
         *                  same key {@code LodSupport} maps its sinks and store roots by
         * @return the index, or null when LOD is off for this world / there is no store
         */
        CsLodPresenceIndex indexFor(String worldName);
    }

    /** Null means "nothing published" == LOD-unaware == the pre-LOD behaviour. */
    private static volatile Provider provider;

    private LodPresence() {
    }

    /** Publish the provider. Pass null to unpublish (server stop). */
    public static void setProvider(final Provider value) {
        provider = value;
    }

    /**
     * The presence index for a world, or null when LOD generation is not active for it.
     *
     * <p>Callers treat null as "do not do any of this" -- see {@code GenerationTask}.
     */
    public static CsLodPresenceIndex indexFor(final String worldName) {
        final Provider current = provider;
        return current == null ? null : current.indexFor(worldName);
    }
}
