package com.kishku7.chunksmith.lod.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which regions have already been handed to a renderer THIS SESSION -- keyed by DIMENSION as well as by
 * region coordinates, and remembering WHICH VERSION of each one we drew.
 *
 * <p>Every pull returns the whole in-radius set, most of it already drawn, and re-injecting those re-pushes
 * terrain the renderer has -- with voxy, hundreds of thousands of sections. So a region is injected once,
 * unless it has actually changed.
 *
 * <p><b>The dimension is part of the key.</b> It used to be a {@code Set<Long>} of packed region x/z
 * alone, and region (0,0) is a DIFFERENT PLACE in every dimension: once the overworld's (0,0) had been
 * injected, the Nether's was considered "already done" and silently skipped forever -- the player walked
 * into the Nether, its LODs never appeared, and every counter and log line reported success.
 *
 * <p><b>The freshness token is part of the VALUE.</b> A pregen does not only create NEW regions -- it keeps
 * GROWING the ones the player is standing on, for hours. Before 3.1.0-beta-4 the injector keyed on
 * (dimension, x, z) alone and threw the re-fetched, bigger file away silently: the far ring of new regions
 * appeared while the terrain under the player's feet stayed frozen at the version they joined on.
 *
 * <p>This is the SESSION half, cleared on disconnect. {@link InjectedIndex} is the on-disk record a join
 * seeds this map from via {@link #seed}: starting empty re-drew the whole in-range store at every join.
 *
 * <p>Deliberately MC-free so it can be unit-tested. Thread-safe: the injector runs off the game thread and
 * the network handler releases regions from another.
 */
public final class InjectedRegions {

    /** (dimension, x, z) -> the freshness token of the version we last handed to a renderer. */
    private final Map<String, Long> injected = new ConcurrentHashMap<>();

    /**
     * Claim a region for injection, offering the freshness token the server advertised for it: succeeds
     * when we have never injected this (dimension, region), or when the version we injected is not the
     * one being offered now. Atomic -- of two concurrent claims on the same region, exactly one wins, and
     * the winner must inject the region or {@link #release} it.
     */
    public boolean claim(final String dimension, final int regionX, final int regionZ, final long hash) {
        final String key = key(dimension, regionX, regionZ);
        final Long previous = this.injected.put(key, hash);
        if (previous == null) {
            return true;
        }
        if (previous == hash) {
            return false;
        }
        // Drawn, but the server has a different version now. Ours -- and `put` has already staked it.
        return true;
    }

    /**
     * Pre-load a claim that a PREVIOUS session made and wrote down -- see {@link InjectedIndex}.
     */
    public void seed(final String dimension, final int regionX, final int regionZ, final long hash) {
        this.injected.put(key(dimension, regionX, regionZ), hash);
    }

    /**
     * Give a claimed region back, so a later refresh retries it rather than skipping it forever.
     *
     * <p><b>Releasing FORGETS the region entirely</b> rather than restoring the previous token: the caller
     * says the injection did not happen, so the honest state is "we do not know what this renderer has",
     * and restoring the token would let an interrupted upgrade leave us believing we drew what we had not.
     */
    public void release(final String dimension, final int regionX, final int regionZ) {
        this.injected.remove(key(dimension, regionX, regionZ));
    }

    public boolean contains(final String dimension, final int regionX, final int regionZ) {
        return this.injected.containsKey(key(dimension, regionX, regionZ));
    }

    public Long injectedHash(final String dimension, final int regionX, final int regionZ) {
        return this.injected.get(key(dimension, regionX, regionZ));
    }

    public int size() {
        return this.injected.size();
    }

    public void clear() {
        this.injected.clear();
    }

    /**
     * The key. A separator that cannot occur in a dimension id (which is validated against
     * {@code [a-z0-9_.-]} by {@link CsLodStore}) keeps "a" + "1,2" from ever colliding with "a1" + ",2".
     */
    static String key(final String dimension, final int regionX, final int regionZ) {
        return dimension + '/' + regionX + ',' + regionZ;
    }
}
