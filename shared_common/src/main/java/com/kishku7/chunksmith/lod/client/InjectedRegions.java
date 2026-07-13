package com.kishku7.chunksmith.lod.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which regions have already been handed to a renderer THIS SESSION -- keyed by DIMENSION as well as by
 * region coordinates.
 *
 * <p>The client keeps pulling as the player travels, and every pull returns the whole set of regions within
 * the renderer's radius -- most of which are already drawn. Injecting those again would re-decode and
 * re-push terrain the renderer already has: with voxy that is hundreds of thousands of sections re-ingested
 * to draw nothing new. So a region is injected exactly once per session.
 *
 * <p><b>The dimension is part of the key, and that is the whole point of this class.</b> It used to be a
 * {@code Set<Long>} of packed region x/z and nothing else -- and region (0,0) is a DIFFERENT PLACE in every
 * dimension. Once the overworld's region (0,0) had been injected, the Nether's region (0,0) was considered
 * "already done" and was silently skipped forever: the player walked into the Nether and its LODs never
 * appeared, while every counter and every log line reported success. Two dimensions, one keyspace, no
 * collision detection. Region coordinates are only meaningful WITH the dimension they belong to -- so they
 * are never a key without it.
 *
 * <p>Deliberately MC-free so it can be unit-tested. Thread-safe: the injector runs off the game thread and
 * the network handler releases regions from another.
 */
public final class InjectedRegions {

    private final Set<String> injected = ConcurrentHashMap.newKeySet();

    /**
     * Claim a region for injection.
     *
     * @return true if this (dimension, region) has NOT been injected yet -- the caller now owns it and must
     *         either inject it or {@link #release} it
     */
    public boolean claim(final String dimension, final int regionX, final int regionZ) {
        return injected.add(key(dimension, regionX, regionZ));
    }

    /**
     * Give a claimed region back, so a later refresh retries it rather than skipping it forever.
     *
     * <p>Used when the region could not be read, or when no renderer was ready in time, or when the player
     * left the dimension before we got to it.
     */
    public void release(final String dimension, final int regionX, final int regionZ) {
        injected.remove(key(dimension, regionX, regionZ));
    }

    /** Has this exact (dimension, region) been injected? */
    public boolean contains(final String dimension, final int regionX, final int regionZ) {
        return injected.contains(key(dimension, regionX, regionZ));
    }

    /** How many (dimension, region) pairs are held. */
    public int size() {
        return injected.size();
    }

    /** Forget everything. Called on disconnect -- the store is keyed by server, and so is this. */
    public void clear() {
        injected.clear();
    }

    /**
     * The key. A separator that cannot occur in a dimension id (which is validated against
     * {@code [a-z0-9_.-]} by {@link CsLodStore}) keeps "a" + "1,2" from ever colliding with "a1" + ",2".
     */
    static String key(final String dimension, final int regionX, final int regionZ) {
        return dimension + '/' + regionX + ',' + regionZ;
    }
}
