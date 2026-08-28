package com.kishku7.chunksmith.lod.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which version of which region has already been handed to a renderer this session, keyed by
 * dimension as well as by region coordinates.
 *
 * <p>Every pull returns the whole in-radius set, most of it already drawn, and re-injecting those
 * re-pushes terrain the renderer has; with voxy, hundreds of thousands of sections.
 *
 * <p>The key needs the dimension because it used to be a {@code Set<Long>} of packed region x/z alone,
 * and region (0,0) is a different place in every dimension: once the overworld's (0,0) had been
 * injected, the Nether's was considered "already done" and silently skipped forever. The player walked
 * into the Nether, its LODs never appeared, and every counter and log line reported success.
 *
 * <p>The value needs the freshness token because a pregen keeps GROWING the regions the player is
 * standing on, for hours. Before 3.1.0-beta-4 the injector keyed on (dimension, x, z) alone and threw
 * the re-fetched, bigger file away silently: the far ring of new regions appeared while the terrain
 * under the player's feet stayed frozen at the version they joined on.
 *
 * <p>The session half, cleared on disconnect; {@link InjectedIndex} is the on-disk record a join seeds
 * this map from via {@link #seed}. Thread-safe: the injector runs off the game thread and the network
 * handler releases regions from another.
 */
public final class InjectedRegions {

    /** (dimension, x, z) -> the freshness token of the version we last handed to a renderer. */
    private final Map<String, Long> injected = new ConcurrentHashMap<>();

    /**
     * Claim a region for injection, offering the freshness token the server advertised: succeeds when we have
     * never injected this (dimension, region), or when the version we injected is not the one being offered.
     * Atomic -- of two concurrent claims exactly one wins, and the winner must inject or {@link #release}.
     */
    public boolean claim(String dimension, int regionX, int regionZ, long hash) {
        String key = key(dimension, regionX, regionZ);
        Long previous = this.injected.put(key, hash);
        if (previous == null) {
            return true;
        }
        if (previous == hash) {
            return false;
        }
        // Drawn, but the server has a different version now. Ours, and `put` has already staked it.
        return true;
    }

    /** Pre-load a claim a previous session made and wrote down. See {@link InjectedIndex}. */
    public void seed(String dimension, int regionX, int regionZ, long hash) {
        this.injected.put(key(dimension, regionX, regionZ), hash);
    }

    /**
     * Give a claimed region back, so a later refresh retries it rather than skipping it forever.
     *
     * <p>Releasing forgets the region entirely rather than restoring its previous token; restoring it
     * would let an interrupted upgrade leave us believing we drew what we had not.
     */
    public void release(String dimension, int regionX, int regionZ) {
        this.injected.remove(key(dimension, regionX, regionZ));
    }

    public boolean contains(String dimension, int regionX, int regionZ) {
        return this.injected.containsKey(key(dimension, regionX, regionZ));
    }

    public Long injectedHash(String dimension, int regionX, int regionZ) {
        return this.injected.get(key(dimension, regionX, regionZ));
    }

    public int size() {
        return this.injected.size();
    }

    public void clear() {
        this.injected.clear();
    }

    /**
     * The key. A separator that cannot occur in a dimension id (validated against {@code [a-z0-9_.-]} by
     * {@link CsLodStore}) keeps "a" + "1,2" from colliding with "a1" + ",2".
     */
    static String key(String dimension, int regionX, int regionZ) {
        return dimension + '/' + regionX + ',' + regionZ;
    }
}
